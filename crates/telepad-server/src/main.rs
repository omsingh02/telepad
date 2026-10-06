use clap::Parser;
use std::io::IsTerminal;
use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::Arc;
use std::time::Duration;
use telepad_platform::clipboard::{Clipboard, SystemClipboard};
use telepad_platform::{
    create_input_backend, paths, BackendOptions, InputBackend, RecordingBackend,
};
use telepad_protocol::TELEPAD_PORT;
use telepad_server::console::{self, Command};
use telepad_server::pairing::DEFAULT_PAIRING_WINDOW;
use telepad_server::{PairingMode, Server, ServerConfig, ServerHandle};
use tokio::sync::Notify;

#[derive(Parser, Debug)]
#[command(
    name = "telepad-server",
    version,
    about = "Desktop server for the Telepad app: lets your phone act as this computer's trackpad and keyboard"
)]
struct Cli {
    /// UDP port to listen on
    #[arg(short, long, default_value_t = TELEPAD_PORT)]
    port: u16,

    /// Log in detail (RUST_LOG, if set, takes precedence)
    #[arg(short, long)]
    verbose: bool,

    /// Directory for the identity key and the list of paired phones
    #[arg(long, value_name = "DIR")]
    key_dir: Option<PathBuf>,

    /// Let a new phone pair for SECONDS (default 300) after startup, or until
    /// one phone has paired. Without this, new phones are only accepted on the
    /// very first run, while nothing is paired yet.
    #[arg(long, value_name = "SECONDS", num_args = 0..=1, default_missing_value = "300")]
    pair: Option<u64>,

    /// Accept and remember every phone that connects, forever. Anyone on your
    /// network will be able to type on this computer. Not recommended.
    #[arg(long)]
    insecure_accept_any_client: bool,

    /// Do not inject input: run the protocol only (for testing and diagnostics)
    #[arg(long)]
    no_input: bool,

    /// macOS: treat the phone's Ctrl as Command (and Win as Control), so
    /// Windows-style shortcuts like Ctrl+C / Ctrl+V work as Copy / Paste
    #[cfg(target_os = "macos")]
    #[arg(long)]
    mac_ctrl_as_cmd: bool,

    /// Linux: type every character through the Ctrl+Shift+U Unicode sequence
    /// instead of as key presses. Use this if your keyboard layout is not
    /// US-compatible and typed text comes out wrong (for example "y" and "z"
    /// swapped). Only GTK and IBus applications accept that sequence.
    #[cfg(target_os = "linux")]
    #[arg(long)]
    text_via_unicode: bool,
}

fn backend_options(args: &Cli) -> BackendOptions {
    #[allow(unused_mut)]
    let mut options = BackendOptions::default();
    #[cfg(target_os = "macos")]
    if args.mac_ctrl_as_cmd {
        options.macos_modifiers = telepad_platform::keymap::macos::ModifierPolicy::CtrlAsCommand;
    }
    #[cfg(target_os = "linux")]
    if args.text_via_unicode {
        options.text_entry = telepad_platform::TextEntry::Unicode;
    }
    #[cfg(not(any(target_os = "macos", target_os = "linux")))]
    let _ = args;
    options
}

fn pairing_mode(args: &Cli) -> PairingMode {
    if args.insecure_accept_any_client {
        PairingMode::AcceptAny
    } else if let Some(seconds) = args.pair {
        PairingMode::Window(Duration::from_secs(seconds))
    } else {
        PairingMode::WhenUnpaired(DEFAULT_PAIRING_WINDOW)
    }
}

fn init_logging(verbose: bool) {
    let default = if verbose {
        "telepad_server=debug,telepad_platform=debug,telepad_crypto=debug,telepad_protocol=debug,info"
    } else {
        "telepad_server=info,telepad_platform=info,info"
    };
    let filter = tracing_subscriber::EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new(default));
    tracing_subscriber::fmt().with_env_filter(filter).init();
}

#[tokio::main]
async fn main() -> ExitCode {
    match run(Cli::parse()).await {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("error: {message}");
            ExitCode::FAILURE
        }
    }
}

async fn run(args: Cli) -> Result<(), String> {
    init_logging(args.verbose);

    let config_dir = args
        .key_dir
        .clone()
        .unwrap_or_else(paths::default_config_dir);
    paths::ensure_private_dir(&config_dir).map_err(|e| {
        format!(
            "cannot create the config directory {}: {e}",
            config_dir.display()
        )
    })?;

    let input: Box<dyn InputBackend> = if args.no_input {
        Box::new(RecordingBackend::new())
    } else {
        create_input_backend(&backend_options(&args)).map_err(|e| {
            format!("{e}\n\n(Use --no-input to run without injecting input, for diagnostics.)")
        })?
    };

    let hostname = hostname::get()
        .map(|h| h.to_string_lossy().into_owned())
        .unwrap_or_else(|_| "Desktop-PC".to_owned());

    let mut config = ServerConfig::new(config_dir);
    config.bind = std::net::SocketAddr::from(([0, 0, 0, 0], args.port));
    config.hostname = hostname;
    config.pairing = pairing_mode(&args);

    let clipboard: telepad_server::clipboard::ClipboardFactory =
        Box::new(|| SystemClipboard::new().map(|c| Box::new(c) as Box<dyn Clipboard>));
    let server = Server::bind(config, input, clipboard)
        .await
        .map_err(|e| e.to_string())?;
    let handle = server.handle();

    let interactive = std::io::stdin().is_terminal();
    print_banner(&server, &handle, args.port, interactive).await;

    let quit = Arc::new(Notify::new());
    if interactive {
        println!(" Type 'help' for commands.");
        spawn_console(handle, Arc::clone(&quit));
    }

    server.run(wait_for_shutdown(quit)).await;
    println!("Telepad server stopped.");
    Ok(())
}

async fn print_banner(server: &Server, handle: &ServerHandle, port: u16, interactive: bool) {
    let status = handle.status().await;
    let pairing = if status.accepts_any_device {
        "OPEN to every device (--insecure-accept-any-client)".to_owned()
    } else if let Some(left) = status.pairing_window {
        format!("OPEN for {} s: connect your phone now", left.as_secs())
    } else {
        let how = if interactive {
            "Type 'pair' to add another"
        } else {
            "Restart with --pair to add another"
        };
        format!("closed ({} phone(s) paired). {how}", status.paired_devices)
    };

    println!("==================================================");
    println!(
        " Telepad Desktop Server v{} (Rust)",
        env!("CARGO_PKG_VERSION")
    );
    println!(" Port:        {port}");
    println!(" Hostname:    {}", server.hostname());
    println!(" Fingerprint: {}", server.fingerprint());
    println!(" Input:       {}", server.input_backend_name());
    println!(" Pairing:     {pairing}");
    println!("==================================================");
}

/// Resolves on Ctrl-C, on SIGTERM (so `systemctl stop` shuts down cleanly and
/// releases held keys), or when the console asks to quit.
async fn wait_for_shutdown(quit: Arc<Notify>) {
    #[cfg(unix)]
    {
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
            () = quit.notified() => {}
        }
    }
    #[cfg(not(unix))]
    {
        tokio::select! {
            _ = tokio::signal::ctrl_c() => {}
            () = quit.notified() => {}
        }
    }
}

fn spawn_console(handle: ServerHandle, quit: Arc<Notify>) {
    let (lines_tx, mut lines_rx) = tokio::sync::mpsc::unbounded_channel::<String>();
    let reader = std::thread::Builder::new()
        .name("telepad-console".into())
        .spawn(move || {
            for line in std::io::stdin().lines() {
                let Ok(line) = line else { break };
                if lines_tx.send(line).is_err() {
                    break;
                }
            }
        });
    if reader.is_err() {
        return;
    }

    tokio::spawn(async move {
        while let Some(line) = lines_rx.recv().await {
            match console::parse(&line) {
                Ok(command) => {
                    if !execute(command, &handle, &quit).await {
                        break;
                    }
                }
                Err(console::ParseError::Empty) => {}
                Err(console::ParseError::Unknown(word)) => {
                    println!("Unknown command '{word}'. Type 'help'.");
                }
                Err(console::ParseError::BadDuration(arg)) => {
                    println!(
                        "'{arg}' is not a valid number of seconds (1 to {}).",
                        console::MAX_PAIRING_SECONDS
                    );
                }
            }
        }
    });
}

/// Runs one console command. Returns false once the console should stop.
async fn execute(command: Command, handle: &ServerHandle, quit: &Notify) -> bool {
    match command {
        Command::Pair(duration) => {
            let duration = duration.unwrap_or(DEFAULT_PAIRING_WINDOW);
            handle.open_pairing(duration).await;
            println!(
                "Pairing is open for {} s. Connect your phone now.",
                duration.as_secs()
            );
        }
        Command::Close => {
            handle.close_pairing().await;
            println!("Pairing closed. Only paired phones can connect.");
        }
        Command::List => {
            let devices = handle.paired_fingerprints().await;
            if devices.is_empty() {
                println!("No phones are paired yet.");
            } else {
                for (i, fingerprint) in devices.iter().enumerate() {
                    println!("  {}. {fingerprint}", i + 1);
                }
            }
        }
        Command::Forget => match handle.forget_all().await {
            Ok(count) => println!("Unpaired {count} phone(s)."),
            Err(err) => println!("Could not update the paired list: {err}"),
        },
        Command::Status => {
            let status = handle.status().await;
            println!("Connected phones: {}", status.sessions);
            println!("Paired phones:    {}", status.paired_devices);
            match (status.accepts_any_device, status.pairing_window) {
                (true, _) => println!("Pairing:          open to every device"),
                (false, Some(left)) => println!("Pairing:          open for {} s", left.as_secs()),
                (false, None) => println!("Pairing:          closed"),
            }
        }
        Command::Help => println!("{}", console::HELP),
        Command::Quit => {
            quit.notify_one();
            return false;
        }
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cli(extra: &[&str]) -> Cli {
        let mut argv = vec!["telepad-server"];
        argv.extend_from_slice(extra);
        Cli::try_parse_from(argv).unwrap()
    }

    #[test]
    fn defaults_are_secure() {
        let args = cli(&[]);
        assert_eq!(args.port, TELEPAD_PORT);
        assert!(matches!(pairing_mode(&args), PairingMode::WhenUnpaired(_)));
        assert!(!args.no_input);
    }

    #[test]
    fn pair_flag_accepts_an_optional_duration() {
        assert_eq!(
            pairing_mode(&cli(&["--pair"])),
            PairingMode::Window(Duration::from_secs(300))
        );
        assert_eq!(
            pairing_mode(&cli(&["--pair", "45"])),
            PairingMode::Window(Duration::from_secs(45))
        );
        assert_eq!(
            pairing_mode(&cli(&["--pair=45"])),
            PairingMode::Window(Duration::from_secs(45))
        );
    }

    #[test]
    fn the_insecure_flag_wins_and_is_spelled_out() {
        assert_eq!(
            pairing_mode(&cli(&["--insecure-accept-any-client", "--pair", "10"])),
            PairingMode::AcceptAny
        );
        assert!(Cli::try_parse_from(["telepad-server", "--accept-any"]).is_err());
    }

    #[test]
    fn other_flags_parse() {
        let args = cli(&[
            "--port",
            "6001",
            "--verbose",
            "--no-input",
            "--key-dir",
            "/tmp/k",
        ]);
        assert_eq!(args.port, 6001);
        assert!(args.verbose && args.no_input);
        assert_eq!(args.key_dir, Some(PathBuf::from("/tmp/k")));
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn text_entry_defaults_to_keys_and_can_be_switched_to_unicode() {
        use telepad_platform::TextEntry;
        assert_eq!(backend_options(&cli(&[])).text_entry, TextEntry::Keys);
        assert_eq!(
            backend_options(&cli(&["--text-via-unicode"])).text_entry,
            TextEntry::Unicode
        );
    }

    #[test]
    fn cli_definition_is_consistent() {
        use clap::CommandFactory;
        Cli::command().debug_assert();
    }
}
