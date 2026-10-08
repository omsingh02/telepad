//! The update, as the rest of the program sees it: a state to show, and two things to ask for (look, install).
//!
//! Everything slow happens on a thread of its own and is reported through [`State`], so that a menu or a page can
//! simply show what the state is and offer the one thing that makes sense in it.

use crate::apply::{self, Plan};
use crate::fetch::{self, Available, Outcome};
use crate::kind::Kind;
use crate::net::Http;
use crate::release::Source;
use crate::settings::Settings;
use crate::system::Commands;
use crate::version::Version;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// What is known about updates, and what is going on.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum State {
    /// Nobody has looked yet.
    Unknown,
    Checking,
    UpToDate,
    /// There is a newer release.
    Available(Available),
    Downloading {
        update: Available,
        done: u64,
        total: Option<u64>,
    },
    Installing(Available),
    /// Put in place: the program is quitting, and the new one starts as it ends.
    Restarting(Available),
    /// Looking did not work, with why.
    CheckFailed(String),
    /// Installing did not work. `how` is what to do by hand, if there is a way; `declined` says the person closed
    /// the password prompt, which is not a failure to make a fuss about.
    InstallFailed {
        update: Available,
        message: String,
        how: Option<String>,
        declined: bool,
    },
}

impl State {
    /// Whether something is going on that a second request must not start over.
    pub fn is_busy(&self) -> bool {
        matches!(
            self,
            State::Checking
                | State::Downloading { .. }
                | State::Installing(_)
                | State::Restarting(_)
        )
    }

    /// The newer release, in whatever state it is being dealt with.
    pub fn update(&self) -> Option<&Available> {
        match self {
            State::Available(update)
            | State::Downloading { update, .. }
            | State::Installing(update)
            | State::Restarting(update)
            | State::InstallFailed { update, .. } => Some(update),
            _ => None,
        }
    }
}

/// What the updater needs from the program around it.
pub struct Config {
    pub http: Arc<dyn Http>,
    pub commands: Arc<dyn Commands>,
    /// The version that is running.
    pub current: Version,
    /// How it was installed.
    pub kind: Kind,
    /// Where the releases are listed and their files are.
    pub source: Source,
    /// Telepad's own folder: the choice is kept here, and downloads go in a folder of their own inside it.
    pub folder: PathBuf,
    /// Whether Telepad starts at login now.
    pub autostart: Arc<dyn Fn() -> bool + Send + Sync>,
    /// Called when an update is in place and the program should quit so that the new one can start.
    pub on_restart: Arc<dyn Fn() + Send + Sync>,
    /// Wait this long after starting before the first look.
    pub first_look_after: Duration,
    /// And look again this often.
    pub look_every: Duration,
}

pub struct Updater {
    config: Config,
    settings: Settings,
    state: Mutex<State>,
}

impl Updater {
    pub fn new(config: Config) -> Arc<Self> {
        let settings = Settings::in_folder(&config.folder);
        Arc::new(Self {
            config,
            settings,
            state: Mutex::new(State::Unknown),
        })
    }

    pub fn state(&self) -> State {
        self.state.lock().unwrap().clone()
    }

    pub fn current(&self) -> &Version {
        &self.config.current
    }

    pub fn kind(&self) -> &Kind {
        &self.config.kind
    }

    pub fn auto_check(&self) -> bool {
        self.settings.auto_check()
    }

    pub fn set_auto_check(&self, on: bool) -> Result<(), String> {
        self.settings.set_auto_check(on)
    }

    /// Looks for a newer release now, on a thread of its own.
    pub fn check(self: &Arc<Self>) {
        let this = Arc::clone(self);
        spawn("telepad-update-check", move || this.check_blocking());
    }

    /// Installs the update that was found, on a thread of its own.
    pub fn install(self: &Arc<Self>) {
        let this = Arc::clone(self);
        spawn("telepad-update-install", move || this.install_blocking());
    }

    /// Looks for a newer release when the program has been running a little while, and once a day after that,
    /// unless the person has switched that off.
    pub fn look_regularly(self: &Arc<Self>) {
        let this = Arc::clone(self);
        spawn("telepad-update-schedule", move || {
            this.forget_old_downloads();
            std::thread::sleep(this.config.first_look_after);
            loop {
                if this.auto_check() {
                    this.check_blocking();
                }
                std::thread::sleep(this.config.look_every);
            }
        });
    }

    /// Asks GitHub and waits for the answer.
    pub fn check_blocking(&self) {
        {
            let mut state = self.state.lock().unwrap();
            if state.is_busy() {
                return;
            }
            *state = State::Checking;
        }
        let outcome = fetch::check(
            &*self.config.http,
            &self.config.source,
            &self.config.current,
            &self.config.kind,
        );
        let next = match outcome {
            Ok(Outcome::UpToDate) => State::UpToDate,
            Ok(Outcome::Available(update)) => State::Available(*update),
            Err(error) => {
                tracing::warn!("could not look for a newer Telepad: {error}");
                State::CheckFailed(error.to_string())
            }
        };
        *self.state.lock().unwrap() = next;
    }

    /// Downloads, checks and installs the update, and waits until that is over.
    pub fn install_blocking(&self) {
        let update = {
            let mut state = self.state.lock().unwrap();
            let update = match &*state {
                State::Available(update) | State::InstallFailed { update, .. } => update.clone(),
                _ => return,
            };
            if update.install.is_none() {
                return;
            }
            *state = State::Downloading {
                update: update.clone(),
                done: 0,
                total: None,
            };
            update
        };
        let fail = |message: String, how: Option<String>, declined: bool| {
            *self.state.lock().unwrap() = State::InstallFailed {
                update: update.clone(),
                message,
                how,
                declined,
            };
        };
        let Some(install) = update.install.as_ref() else {
            return;
        };

        let downloads = self.config.folder.join("download");
        let prepared = std::fs::remove_dir_all(&downloads)
            .or_else(|error| match error.kind() {
                std::io::ErrorKind::NotFound => Ok(()),
                _ => Err(error),
            })
            .and_then(|()| create_private_dir(&downloads.join("unpack")));
        if let Err(error) = prepared {
            return fail(
                format!("Telepad could not make a folder for the download: {error}"),
                None,
                false,
            );
        }

        let downloaded = {
            let mut progress = |done: u64, total: Option<u64>| {
                *self.state.lock().unwrap() = State::Downloading {
                    update: update.clone(),
                    done,
                    total,
                };
            };
            match fetch::download(&*self.config.http, install, &downloads, &mut progress) {
                Ok(file) => file,
                Err(error) => {
                    tracing::warn!("could not download the update: {error}");
                    return fail(error.to_string(), None, false);
                }
            }
        };

        *self.state.lock().unwrap() = State::Installing(update.clone());
        let plan = Plan {
            kind: &self.config.kind,
            file: &downloaded.path,
            sha256: &downloaded.sha256,
            work: &downloads.join("unpack"),
            autostart: (self.config.autostart)(),
            pid: std::process::id(),
        };
        match apply::apply(&*self.config.commands, &plan) {
            Ok(()) => {
                tracing::info!("Telepad {} is in place; restarting", update.release.version);
                *self.state.lock().unwrap() = State::Restarting(update.clone());
                (self.config.on_restart)();
            }
            Err(apply::Error::Declined) => fail(apply::Error::Declined.to_string(), None, true),
            Err(apply::Error::ByHand { why, how }) => fail(why, how, false),
            Err(apply::Error::Failed(message)) => {
                tracing::warn!("could not install the update: {message}");
                fail(message, None, false);
            }
        }
    }

    /// What an earlier update left behind.
    fn forget_old_downloads(&self) {
        let _ = std::fs::remove_dir_all(self.config.folder.join("download"));
    }
}

fn spawn(name: &str, work: impl FnOnce() + Send + 'static) {
    if let Err(error) = std::thread::Builder::new().name(name.into()).spawn(work) {
        tracing::warn!("could not start {name}: {error}");
    }
}

/// A folder (and the ones above it, as needed) that only its owner can enter.
fn create_private_dir(path: &std::path::Path) -> std::io::Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        std::fs::DirBuilder::new()
            .recursive(true)
            .mode(0o700)
            .create(path)
    }
    #[cfg(not(unix))]
    {
        std::fs::create_dir_all(path)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::kind::PackageFormat;
    use crate::net::fake::FakeHttp;
    use crate::net::NetError;
    use crate::sums;
    use crate::system::fake::Script;
    use std::sync::atomic::{AtomicBool, Ordering};

    const LIST: &str = "https://feed.example/releases.atom";
    const ASSET: &str = "telepad-v2.0.0-alpha.4-linux-x86_64.deb";
    const BODY: &[u8] = b"a package, pretend";

    fn folder(name: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("telepad-updater-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    /// A network that has release alpha.4 with its package and checksums.
    fn network(body: &[u8]) -> FakeHttp {
        let base = "https://github.com/omsingh02/telepad/releases/download/v2.0.0-alpha.4";
        FakeHttp::default()
            .with(LIST, crate::release::feed_of(&["v2.0.0-alpha.4"]))
            .with(
                &format!("{base}/SHA256SUMS"),
                format!(
                    "{}  {ASSET}\n",
                    sums::to_hex(&sums::hash_reader(BODY).unwrap())
                ),
            )
            .with(&format!("{base}/{ASSET}"), body)
    }

    struct Setup {
        updater: Arc<Updater>,
        commands: Arc<Script>,
        restarted: Arc<AtomicBool>,
        folder: PathBuf,
    }

    fn setup(name: &str, current: &str, http: FakeHttp, kind: Kind) -> Setup {
        let folder = folder(name);
        let commands = Arc::new(Script::default());
        let restarted = Arc::new(AtomicBool::new(false));
        let flag = Arc::clone(&restarted);
        let updater = Updater::new(Config {
            http: Arc::new(http),
            commands: commands.clone(),
            current: current.parse().unwrap(),
            kind,
            source: Source {
                list_url: LIST.into(),
                ..Source::github()
            },
            folder: folder.clone(),
            autostart: Arc::new(|| true),
            on_restart: Arc::new(move || flag.store(true, Ordering::SeqCst)),
            first_look_after: Duration::ZERO,
            look_every: Duration::from_secs(3600),
        });
        Setup {
            updater,
            commands,
            restarted,
            folder,
        }
    }

    fn deb() -> Kind {
        Kind::LinuxPackage(PackageFormat::Deb)
    }

    #[test]
    fn it_starts_knowing_nothing_and_finds_the_newer_release() {
        let s = setup("find", "2.0.0-alpha.3", network(BODY), deb());
        assert_eq!(s.updater.state(), State::Unknown);
        s.updater.check_blocking();
        let State::Available(update) = s.updater.state() else {
            panic!("{:?}", s.updater.state())
        };
        assert_eq!(update.release.tag, "v2.0.0-alpha.4");
        assert!(update.install.is_some());
        let _ = std::fs::remove_dir_all(&s.folder);
    }

    #[test]
    fn being_up_to_date_and_being_offline_are_different_answers() {
        let s = setup("same", "2.0.0-alpha.4", network(BODY), deb());
        s.updater.check_blocking();
        assert_eq!(s.updater.state(), State::UpToDate);

        let offline = FakeHttp::default().failing(LIST, NetError::Unreachable);
        let s2 = setup("offline", "2.0.0-alpha.3", offline, deb());
        s2.updater.check_blocking();
        let State::CheckFailed(message) = s2.updater.state() else {
            panic!()
        };
        assert!(message.contains("could not reach GitHub"), "{message}");
        let _ = std::fs::remove_dir_all(&s.folder);
        let _ = std::fs::remove_dir_all(&s2.folder);
    }

    #[test]
    fn installing_downloads_checks_installs_and_asks_the_program_to_quit() {
        let s = setup("install", "2.0.0-alpha.3", network(BODY), deb());
        s.updater.check_blocking();
        s.updater.install_blocking();

        assert!(
            matches!(s.updater.state(), State::Restarting(_)),
            "{:?}",
            s.updater.state()
        );
        assert!(
            s.restarted.load(Ordering::SeqCst),
            "the program was asked to quit"
        );
        let ran = s.commands.ran();
        let download = s.folder.join("download").join(ASSET);
        let sum = sums::to_hex(&sums::hash_reader(BODY).unwrap());
        assert!(
            ran.iter().any(|line| line.starts_with("pkexec /bin/sh -c ")
                && line.ends_with(&format!(
                    "telepad-update {} {sum} {ASSET} env DEBIAN_FRONTEND=noninteractive apt-get install -y",
                    download.display()
                ))),
            "{ran:?}"
        );
        assert_eq!(std::fs::read(&download).unwrap(), BODY);
        assert_eq!(
            s.commands.spawned().len(),
            1,
            "the new program is started once this one is gone"
        );
        let _ = std::fs::remove_dir_all(&s.folder);
    }

    #[test]
    fn a_download_that_fails_its_checksum_is_never_installed() {
        let s = setup(
            "tampered",
            "2.0.0-alpha.3",
            network(b"a package, PRETEND"),
            deb(),
        );
        s.updater.check_blocking();
        s.updater.install_blocking();
        let State::InstallFailed {
            message, declined, ..
        } = s.updater.state()
        else {
            panic!("{:?}", s.updater.state())
        };
        assert!(
            message.contains("did not match its published checksum"),
            "{message}"
        );
        assert!(!declined);
        assert!(
            s.commands
                .ran()
                .iter()
                .all(|line| !line.contains("apt-get")),
            "nothing was installed"
        );
        assert!(!s.restarted.load(Ordering::SeqCst));
        assert!(
            !s.folder.join("download").join(ASSET).exists(),
            "the bad file is gone"
        );
        let _ = std::fs::remove_dir_all(&s.folder);
    }

    #[test]
    fn a_closed_password_prompt_can_be_tried_again() {
        let s = setup("declined", "2.0.0-alpha.3", network(BODY), deb());
        s.commands.exit("pkexec", 126);
        s.updater.check_blocking();
        s.updater.install_blocking();
        assert!(matches!(
            s.updater.state(),
            State::InstallFailed { declined: true, .. }
        ));
        assert!(!s.restarted.load(Ordering::SeqCst));

        // Again, and this time the password is given.
        s.commands.exit_codes.lock().unwrap().clear();
        s.updater.install_blocking();
        assert!(matches!(s.updater.state(), State::Restarting(_)));
        assert!(s.restarted.load(Ordering::SeqCst));
        let _ = std::fs::remove_dir_all(&s.folder);
    }

    #[test]
    fn an_update_that_has_to_be_done_by_hand_says_how() {
        let s = setup("hand", "2.0.0-alpha.3", network(BODY), deb());
        s.commands.missing("pkexec");
        s.updater.check_blocking();
        s.updater.install_blocking();
        let State::InstallFailed { how, .. } = s.updater.state() else {
            panic!()
        };
        assert!(how.unwrap().starts_with("sudo apt-get install -y "));
        let _ = std::fs::remove_dir_all(&s.folder);
    }

    #[test]
    fn a_copy_that_cannot_update_itself_is_told_of_the_release_and_nothing_is_downloaded() {
        let http = network(BODY);
        let asked = Arc::clone(&http.asked);
        let s = setup(
            "manual",
            "2.0.0-alpha.3",
            http,
            Kind::Unsupported("Homebrew installed Telepad.".into()),
        );
        s.updater.check_blocking();
        let State::Available(update) = s.updater.state() else {
            panic!()
        };
        assert_eq!(
            update.by_hand.as_deref(),
            Some("Homebrew installed Telepad.")
        );
        s.updater.install_blocking();
        assert!(
            matches!(s.updater.state(), State::Available(_)),
            "asking to install does nothing"
        );
        assert_eq!(asked.lock().unwrap().len(), 1, "only the list was fetched");
        let _ = std::fs::remove_dir_all(&s.folder);
    }

    #[test]
    fn nothing_is_installed_unless_an_update_was_found_first() {
        let s = setup("none", "2.0.0-alpha.3", network(BODY), deb());
        s.updater.install_blocking();
        assert_eq!(s.updater.state(), State::Unknown);
        assert!(s.commands.ran().is_empty());
        let _ = std::fs::remove_dir_all(&s.folder);
    }

    #[test]
    fn a_second_look_while_one_is_under_way_does_not_start_over() {
        let s = setup("busy", "2.0.0-alpha.3", network(BODY), deb());
        *s.updater.state.lock().unwrap() = State::Checking;
        s.updater.check_blocking();
        assert_eq!(s.updater.state(), State::Checking, "left alone");
        let _ = std::fs::remove_dir_all(&s.folder);
    }

    #[test]
    fn the_regular_look_happens_by_itself_unless_it_is_switched_off() {
        let s = setup("regular", "2.0.0-alpha.3", network(BODY), deb());
        s.updater.set_auto_check(false).unwrap();
        s.updater.look_regularly();
        std::thread::sleep(Duration::from_millis(300));
        assert_eq!(s.updater.state(), State::Unknown, "the person said no");

        let s2 = setup("regular2", "2.0.0-alpha.3", network(BODY), deb());
        s2.updater.look_regularly();
        for _ in 0..50 {
            if matches!(s2.updater.state(), State::Available(_)) {
                break;
            }
            std::thread::sleep(Duration::from_millis(50));
        }
        assert!(
            matches!(s2.updater.state(), State::Available(_)),
            "{:?}",
            s2.updater.state()
        );
        let _ = std::fs::remove_dir_all(&s.folder);
        let _ = std::fs::remove_dir_all(&s2.folder);
    }
}
