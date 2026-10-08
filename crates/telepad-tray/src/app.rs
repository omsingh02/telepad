//! The program: the server, the page with the code, and the tray icon, started in that order and stopped in
//! the reverse.

use crate::fatal;
use crate::instance;
use crate::logging;
use crate::panel::{Control, Identity, Panel, PanelStatus, SetupView, UpdateView};
use crate::setup;
use crate::status;
use crate::tray::{self, Command, Commands, Menu};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock, Weak};
use std::time::Duration;
use telepad_platform::access::{self, Block};
use telepad_platform::autostart;
use telepad_protocol::TELEPAD_PORT;
use telepad_server::launch::{self, Input};
use telepad_server::pairing::DEFAULT_INVITATION_TTL;
use telepad_server::{Invite, ServerHandle};
use telepad_update::net::{Github, Http};
use telepad_update::release::Source;
use telepad_update::system::{self, Commands as RunCommands, System};
use telepad_update::updater::{Config as UpdateConfig, State as UpdateState, Updater};
use telepad_update::version::Version;
use tokio::sync::Notify;
use tokio::task::JoinHandle;
use tracing::{info, warn};

/// How often the menu's status line is brought up to date.
const MENU_REFRESH: Duration = Duration::from_secs(2);

/// How long to wait for the server to let go of held keys before quitting regardless.
const SHUTDOWN_PATIENCE: Duration = Duration::from_secs(3);

/// How long after starting Telepad first looks for a newer version, and how often it looks after that. The wait
/// keeps the check out of the way of everything a start at login is busy with.
const FIRST_LOOK_AFTER: Duration = Duration::from_secs(20);
const LOOK_EVERY: Duration = Duration::from_secs(24 * 60 * 60);

/// What Telepad is started with at login: quietly, so that no page opens by itself.
const AT_LOGIN: &[&str] = &["--background"];

#[derive(clap::Parser, Debug)]
#[command(
    name = "telepad",
    version = telepad_protocol::RELEASE_VERSION,
    about = "Telepad: your phone as a trackpad and keyboard for this computer, from the tray"
)]
pub struct Cli {
    /// UDP port to listen on
    #[arg(short, long, default_value_t = TELEPAD_PORT)]
    pub port: u16,

    /// Log in detail to the error stream
    #[arg(short, long)]
    pub verbose: bool,

    /// Directory for the identity key and the list of paired phones
    #[arg(long, value_name = "DIR")]
    pub key_dir: Option<PathBuf>,

    /// Do not open the page with the QR code on the very first run
    #[arg(long)]
    pub no_open: bool,

    /// Run the protocol without injecting input, for finding out what is wrong
    #[arg(long)]
    pub no_input: bool,

    /// Started at login: stay quiet, and do not open the page by itself (where there is no tray icon to click, start
    /// Telepad again to see the page)
    #[arg(long)]
    pub background: bool,

    /// Ask the Telepad that is running to quit, wait until it has, and exit (for installers and scripts)
    #[arg(long)]
    pub quit: bool,

    /// Look for a newer Telepad, say what was found, and exit
    #[arg(long)]
    pub check_update: bool,
}

pub fn run(cli: Cli) -> Result<(), String> {
    let config_dir = cli
        .key_dir
        .clone()
        .unwrap_or_else(telepad_platform::paths::default_config_dir);

    if cli.check_update {
        return check_update(&config_dir);
    }

    // Starting Telepad again is how it is opened again (the Start menu, Spotlight, the launcher), so when one is
    // running already, show its page rather than fight it for the port.
    let running_page = if cli.quit {
        None
    } else {
        instance::running_page(&config_dir)
    };

    // Only the copy that stays keeps a log file; one that just hands over or asks the other to quit writes none.
    let stays = !cli.quit && running_page.is_none();
    if stays {
        // The folder has to be there for the log; the server reports it properly if it cannot be made.
        let _ = telepad_platform::paths::ensure_private_dir(&config_dir);
    }
    let log_file = logging::init(cli.verbose, stays.then_some(config_dir.as_path()));

    if cli.quit {
        // Said on the error stream only: an installer runs this with nobody to click a message away.
        match instance::quit_running(&config_dir) {
            instance::Quit::NotRunning => eprintln!("telepad: Telepad is not running."),
            instance::Quit::Done => eprintln!("telepad: Telepad has quit."),
            instance::Quit::Stuck => {
                eprintln!("telepad: Telepad was asked to quit but is still running.")
            }
        }
        return Ok(());
    }
    if let Some(url) = running_page {
        eprintln!("telepad: Telepad is already running.");
        if !cli.no_open {
            if let Err(error) = open::that_detached(&url) {
                warn!("could not open the browser: {error}");
            }
        }
        return Ok(());
    }

    info!(
        "Telepad {} starting on {} ({})",
        telepad_protocol::RELEASE_VERSION,
        std::env::consts::OS,
        std::env::consts::ARCH
    );
    if let Some(path) = &log_file {
        info!("writing this log to {}", path.display());
    }

    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .thread_name("telepad")
        .build()
        .map_err(|error| format!("cannot start the program's worker threads: {error}"))?;

    // 1. The server. Everything that can go wrong at startup goes wrong here, and has words for it.
    let server = runtime.block_on(launch::start(launch::Options {
        port: cli.port,
        config_dir: cli.key_dir.clone(),
        input: if cli.no_input {
            Input::Discard
        } else {
            // A computer that has not let Telepad type yet still gets the program, with a way to allow it.
            Input::SystemWhenAllowed(Default::default())
        },
        ..launch::Options::new()
    }))?;
    let identity = Identity {
        name: server.hostname().to_owned(),
        fingerprint: server.fingerprint().to_owned(),
        log_file: log_file.map(|path| path.display().to_string()),
    };
    let handle = server.handle();
    let first_run = runtime.block_on(handle.status()).paired_devices == 0;
    let checks_input = !cli.no_input;
    let blocked_at_start = checks_input && access::input_blocked().is_some();

    let stop_server = Arc::new(Notify::new());
    let stopping = Arc::clone(&stop_server);
    let serving = runtime.spawn(server.run(async move { stopping.notified().await }));

    let shared = Arc::new(Shared {
        runtime: runtime.handle().clone(),
        server: handle,
        config_dir,
        stop_server,
        checks_input,
        allowing: Mutex::new(setup::Progress::default()),
        serving: Mutex::new(Some(serving)),
        ui: Mutex::new(None),
        page: Mutex::new(None),
        opened_at_start: AtomicBool::new(false),
        quitting: AtomicBool::new(false),
        updater: OnceLock::new(),
    });
    let restart = Arc::downgrade(&shared);
    let _ = shared
        .updater
        .set(make_updater(&shared.config_dir, restart, || {}));
    shared.updater().look_regularly();

    // 2. The page with the QR code, which lives as long as the program does.
    let panel = match Panel::start(Arc::new(Controls(Arc::clone(&shared))), identity) {
        Ok(panel) => {
            *shared.page.lock().unwrap() = Some(panel.url().to_owned());
            // So that starting Telepad again finds this one.
            if let Err(error) = instance::announce(&shared.config_dir, panel.url()) {
                warn!("could not leave the page's address for the next start: {error}");
            }
            Some(panel)
        }
        Err(error) => {
            warn!("could not start the page with the QR code: {error}");
            None
        }
    };

    // 3. Starting at login: if it is on, make sure it points at this program (it may have been moved or updated).
    if let Ok(launch) = autostart::Launch::current(AT_LOGIN) {
        if let Err(error) = autostart::refresh(&launch) {
            warn!("could not update the entry that starts Telepad at login: {error}");
        }
    }

    // Unix programs are asked to stop with a signal (logging out, `kill`, Ctrl-C in a terminal): do it properly.
    #[cfg(unix)]
    {
        let shared = Arc::clone(&shared);
        runtime.spawn(async move {
            use tokio::signal::unix::{signal, SignalKind};
            let mut terminate = signal(SignalKind::terminate()).ok();
            tokio::select! {
                _ = tokio::signal::ctrl_c() => {}
                _ = async {
                    match terminate.as_mut() {
                        Some(stream) => { stream.recv().await; }
                        None => std::future::pending::<()>().await,
                    }
                } => {}
            }
            shared.begin_quit();
        });
    }

    // 4. The tray. On Windows and macOS this does not return: it ends the program when it is stopped.
    let menu = runtime.block_on(shared.menu());
    let commands: Commands = {
        let shared = Arc::clone(&shared);
        Arc::new(move |command| shared.command(command))
    };
    let ready = {
        let shared = Arc::clone(&shared);
        // The page opens by itself when the code is the first thing wanted, or when the system is not letting
        // Telepad type (and the person is there to be asked: not at login).
        move |ui: tray::Handle| {
            shared.tray_is_up(
                ui,
                (first_run || blocked_at_start) && !cli.no_open && !cli.background,
            )
        }
    };
    let background = cli.background;
    let unavailable = {
        let shared = Arc::clone(&shared);
        move |reason: String| {
            warn!("no tray icon: {reason}");
            // Nothing to click, so the page opens by itself: it has a Quit button, and says how to start again.
            // Not at login, though: a page that opens at every sign-in is a nuisance, and starting Telepad again
            // (from the applications menu) shows it.
            if !background {
                shared.open_page();
                shared.opened_at_start.store(true, Ordering::SeqCst);
            }
        }
    };
    tray::run(runtime.handle(), menu, commands, ready, unavailable);

    drop(panel);
    info!("Telepad stopped");
    Ok(())
}

/// What the program's parts share.
struct Shared {
    runtime: tokio::runtime::Handle,
    server: ServerHandle,
    /// The program's folder, where the page's address is left for the next start.
    config_dir: PathBuf,
    stop_server: Arc<Notify>,
    /// Whether input is really injected, and so whether the system's permission for it matters.
    checks_input: bool,
    /// How asking the system for permission to type and click is going.
    allowing: Mutex<setup::Progress>,
    serving: Mutex<Option<JoinHandle<()>>>,
    ui: Mutex<Option<tray::Handle>>,
    /// The address of the page with the QR code, if it started.
    page: Mutex<Option<String>>,
    /// The page was opened already because there is no tray icon, so the first run need not open it again.
    opened_at_start: AtomicBool,
    quitting: AtomicBool,
    /// Looks for newer versions and installs them; set as soon as this exists.
    updater: OnceLock<Arc<Updater>>,
}

impl Shared {
    /// The icon is up. From now on the menu is kept current.
    fn tray_is_up(self: &Arc<Self>, ui: tray::Handle, open_page: bool) {
        *self.ui.lock().unwrap() = Some(ui.clone());
        if self.quitting.load(Ordering::SeqCst) {
            // Asked to quit before the icon was up.
            ui.stop();
            return;
        }

        let shared = Arc::clone(self);
        self.runtime.spawn(async move {
            let mut shown: Option<Menu> = None;
            loop {
                if shared.quitting.load(Ordering::SeqCst) {
                    return;
                }
                let menu = shared.menu().await;
                if shown.as_ref() != Some(&menu) {
                    ui.update(menu.clone());
                    shown = Some(menu);
                }
                tokio::time::sleep(MENU_REFRESH).await;
            }
        });

        // Nothing is paired: the first thing to want is the code, so there it is.
        if open_page && !self.opened_at_start.load(Ordering::SeqCst) {
            self.open_page();
        }
    }

    fn updater(&self) -> &Arc<Updater> {
        self.updater.get().expect("the updater is set at the start")
    }

    /// What is in the way of typing and clicking right now, if anything.
    fn blocked(&self) -> Option<Block> {
        if self.checks_input {
            access::input_blocked()
        } else {
            None
        }
    }

    /// What the menu says right now.
    async fn menu(&self) -> Menu {
        let blocked = self.blocked();
        Menu {
            status: status::describe(&self.server.status().await, blocked),
            autostart: autostart::is_enabled(),
            allow: blocked.map(|block| setup::words(block).menu),
            update: status::update_label(&self.updater().state()),
        }
    }

    /// Asks the system for permission to type and click. The asking waits for the person (a password prompt),
    /// so it is done on a thread of its own; the page and the menu show how it goes.
    fn allow(self: &Arc<Self>, open_page_if_it_fails: bool) {
        let Some(block) = self.blocked() else {
            return;
        };
        {
            let mut allowing = self.allowing.lock().unwrap();
            if allowing.working {
                return;
            }
            *allowing = setup::Progress {
                working: true,
                ..Default::default()
            };
        }
        let shared = Arc::clone(self);
        let started = std::thread::Builder::new()
            .name("telepad-allow".into())
            .spawn(move || {
                let outcome = access::allow(block);
                let failed = outcome.is_err();
                if let Err(error) = &outcome {
                    warn!("could not get permission to type and click: {error:?}");
                }
                *shared.allowing.lock().unwrap() = setup::Progress::after(block, outcome);
                shared.refresh_menu();
                // From the menu there is nowhere to read what went wrong, so the page is opened for it.
                if failed && open_page_if_it_fails {
                    shared.open_page();
                }
            });
        if let Err(error) = started {
            warn!("could not start asking for permission: {error}");
            self.allowing.lock().unwrap().working = false;
        }
    }

    /// What the page says about permission to type and click.
    fn setup_view(&self) -> SetupView {
        let block = self.blocked();
        let allowing = self.allowing.lock().unwrap().clone();
        // How the last attempt went matters only while the permission is still missing.
        SetupView {
            block,
            working: block.is_some() && allowing.working,
            note: block.and(allowing.note),
            terminal: block.and(allowing.terminal),
        }
    }

    /// Brings the menu up to date at once, without waiting for the next turn of the refresh. It does not wait
    /// for the server itself, since a choice from the menu may arrive on one of the runtime's own threads.
    fn refresh_menu(self: &Arc<Self>) {
        let shared = Arc::clone(self);
        self.runtime.spawn(async move {
            let menu = shared.menu().await;
            if let Some(ui) = shared.ui.lock().unwrap().clone() {
                ui.update(menu);
            }
        });
    }

    /// A choice from the menu.
    fn command(self: &Arc<Self>, command: Command) {
        match command {
            Command::Pair => self.open_page(),
            Command::Allow => self.allow(true),
            Command::Update => {
                // Looking is what the item says when nothing is known; otherwise it shows what is.
                let state = self.updater().state();
                if state.update().is_none() && !state.is_busy() {
                    self.updater().check();
                }
                self.open_page();
            }
            Command::ToggleAutostart => {
                let was_on = autostart::is_enabled();
                if let Err(message) = set_autostart(!was_on) {
                    fatal::show(&format!(
                        "Telepad could not change starting at login. {message}"
                    ));
                }
                self.refresh_menu();
            }
            Command::Quit => self.begin_quit(),
        }
    }

    /// Opens the page with the QR code in the browser. Opening can wait on the browser starting, so it is done
    /// off whatever thread asked.
    fn open_page(&self) {
        let Some(url) = self.page.lock().unwrap().clone() else {
            warn!("there is no page to open");
            return;
        };
        std::thread::spawn(move || {
            if let Err(error) = open::that(&url) {
                warn!("could not open the browser: {error}");
            }
        });
    }

    /// Stops everything, in the right order: the server (letting go of keys a phone was holding), then the icon.
    /// It does not wait for itself, since it may be called from a thread that the server's runtime is waiting on.
    fn begin_quit(self: &Arc<Self>) {
        if self.quitting.swap(true, Ordering::SeqCst) {
            return;
        }
        // At once, so that a start during the shutdown does not hand over to a copy that is going away.
        if let Some(url) = self.page.lock().unwrap().clone() {
            instance::withdraw(&self.config_dir, &url);
        }
        let shared = Arc::clone(self);
        self.runtime.spawn(async move {
            shared.stop_server.notify_one();
            let serving = shared.serving.lock().unwrap().take();
            if let Some(serving) = serving {
                let _ = tokio::time::timeout(SHUTDOWN_PATIENCE, serving).await;
            }
            if let Some(ui) = shared.ui.lock().unwrap().clone() {
                ui.stop();
            }
        });
    }
}

/// Turns starting at login on or off.
fn set_autostart(on: bool) -> Result<(), String> {
    if on {
        let launch = autostart::Launch::current(AT_LOGIN)
            .map_err(|error| format!("It cannot tell where it is: {error}"))?;
        autostart::enable(&launch).map_err(|error| error.to_string())
    } else {
        autostart::disable().map_err(|error| error.to_string())
    }
}

/// What the page with the QR code can ask of the program.
struct Controls(Arc<Shared>);

impl Control for Controls {
    fn new_invite(&self) -> Option<Invite> {
        // Called from the page's own thread, which is not one of the runtime's, so it may wait for the server.
        Some(
            self.0
                .runtime
                .block_on(self.0.server.create_invite(DEFAULT_INVITATION_TTL)),
        )
    }

    fn status(&self) -> PanelStatus {
        let status = self.0.runtime.block_on(self.0.server.status());
        PanelStatus {
            paired: status.paired_devices,
            connected: status.sessions,
        }
    }

    fn autostart(&self) -> bool {
        autostart::is_enabled()
    }

    fn set_autostart(&self, on: bool) -> Result<(), String> {
        set_autostart(on)
    }

    fn setup(&self) -> SetupView {
        self.0.setup_view()
    }

    fn update(&self) -> UpdateView {
        let updater = self.0.updater();
        UpdateView {
            current: updater.current().to_string(),
            state: updater.state(),
            auto_check: updater.auto_check(),
        }
    }

    fn check_for_updates(&self) {
        self.0.updater().check();
    }

    fn install_update(&self) {
        self.0.updater().install();
    }

    fn set_auto_update_check(&self, on: bool) -> Result<(), String> {
        self.0.updater().set_auto_check(on)
    }

    fn allow(&self) {
        self.0.allow(false);
    }

    fn quit(&self) {
        self.0.begin_quit();
    }
}

/// Sets up the updater: how this copy was installed decides what it can do, and where it looks is GitHub's
/// list of this project's releases (a development build can be pointed at a server on this computer instead).
fn make_updater(
    config_dir: &Path,
    quit: Weak<Shared>,
    before_quit: impl Fn() + Send + Sync + 'static,
) -> Arc<Updater> {
    let commands: Arc<dyn RunCommands> = Arc::new(System);
    let user_agent = format!("Telepad/{}", telepad_protocol::RELEASE_VERSION);
    let (source, http) = development_source(&user_agent).unwrap_or_else(|| {
        (
            Source::github(),
            Arc::new(Github::new(&user_agent)) as Arc<dyn Http>,
        )
    });
    let folder = config_dir.join("updates");
    if let Err(error) = telepad_platform::paths::ensure_private_dir(&folder) {
        warn!("could not make the folder for updates: {error}");
    }
    Updater::new(UpdateConfig {
        http,
        kind: system::detect(&*commands),
        commands,
        current: telepad_protocol::RELEASE_VERSION
            .parse::<Version>()
            .unwrap_or_else(|_| "0.0.0".parse().expect("0.0.0 is a version")),
        source,
        folder,
        autostart: Arc::new(autostart::is_enabled),
        on_restart: Arc::new(move || {
            before_quit();
            if let Some(shared) = quit.upgrade() {
                shared.begin_quit();
            }
        }),
        first_look_after: FIRST_LOOK_AFTER,
        look_every: LOOK_EVERY,
    })
}

/// A development build can look for releases on this computer instead of on GitHub, to try the whole update
/// from one end to the other. A release build has no such way, so no setting can send it anywhere else.
#[cfg(debug_assertions)]
fn development_source(user_agent: &str) -> Option<(Source, Arc<dyn Http>)> {
    let origin = std::env::var("TELEPAD_DEV_UPDATE_ORIGIN").ok()?;
    let source = Source::local(&origin);
    warn!("development build: looking for updates at {origin}, not on GitHub");
    Some((source.clone(), Arc::new(Github::local(user_agent, source))))
}

#[cfg(not(debug_assertions))]
fn development_source(_user_agent: &str) -> Option<(Source, Arc<dyn Http>)> {
    None
}

/// `telepad --check-update`: looks, says what it found, and exits. It installs nothing.
fn check_update(config_dir: &Path) -> Result<(), String> {
    let updater = make_updater(config_dir, Weak::new(), || {});
    updater.check_blocking();
    match updater.state() {
        UpdateState::UpToDate => {
            println!("Telepad {} is up to date.", updater.current());
            Ok(())
        }
        UpdateState::Available(update) => {
            println!(
                "Telepad {} is available (this is {}): {}",
                update.release.version,
                updater.current(),
                update.release.page
            );
            match (&update.install, &update.by_hand) {
                (Some(_), _) => println!(
                    "This copy can install it: choose \"Update to {}…\" in Telepad's menu.",
                    update.release.version
                ),
                (None, Some(why)) => println!("{why}"),
                (None, None) => {}
            }
            Ok(())
        }
        UpdateState::CheckFailed(why) => {
            eprintln!("telepad: could not check for updates: {why}");
            std::process::exit(2)
        }
        other => Err(format!("unexpected update state: {other:?}")),
    }
}
