# Contributing to Telepad

Thank you for wanting to help. Bug reports, ideas, documentation fixes and patches are all welcome.

- **Found a bug or have an idea?** Open an [issue](https://github.com/omsingh02/telepad/issues/new/choose). For a bug, say what you did, what you expected, what happened, and your phone, PC operating system and Telepad versions (the app's *Settings → About*, and the PC page's footer or `telepad --version`), and attach the desktop app's log if you can (the README says where it is).
- **Have a question?** Ask in [Discussions](https://github.com/omsingh02/telepad/discussions).
- Everyone taking part is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
- **Found a security problem?** Please do not open a public issue. See [SECURITY.md](SECURITY.md).
- **Want to change something big?** Open an issue first so we can agree on the direction before you spend a weekend on it.

## How the project is laid out

| | |
| :--- | :--- |
| `crates/telepad-protocol` | The wire protocol: message types and their encoding. No I/O. |
| `crates/telepad-crypto` | The Noise IK handshake, the replay window, key storage, fingerprints. |
| `crates/telepad-platform` | The operating-system layer: Windows `SendInput`, Linux `uinput`, macOS CoreGraphics, clipboard, network interfaces, config paths. |
| `crates/telepad-server` | The desktop server: discovery, pairing policy, invitations (QR codes), sessions, console. |
| `crates/telepad-update` | Looks for a newer release (GitHub's release feed and the release's `SHA256SUMS`), downloads and checks it, and installs it the way that copy was installed. The Android app has its own version of the same in `update/`. |
| `crates/telepad-tray` | The `telepad` app: tray icon, the page with the QR code, start at login, one copy at a time, the log file, the permission step and the update buttons. |
| `packaging/` | The Windows installer, the macOS app and disk image, the Linux tarball, icons, and the package-manager manifests. See [packaging/README.md](packaging/README.md). |
| `android/` | The Android app (Kotlin, Jetpack Compose). See the structure in the [README](README.md#android-app-structure). |

The protocol is implemented twice, in Rust and in Kotlin. A change to it has to land in both halves, together, and in the protocol list in the README.

## Building and testing

### Desktop server (Rust)

You need a current stable Rust toolchain. On Linux also `libxcb-shape0-dev` and `libxcb-xfixes0-dev` (Debian and Ubuntu names) for the clipboard.

```bash
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
cargo fmt --all
```

CI runs the tests and clippy on Windows, Linux and macOS, checks formatting, and builds and tries the installers and packages. A warning is a failure.

When you add or update a dependency, also run:

```bash
scripts/check-dependencies.sh      # known vulnerabilities, licenses we can ship, sources (needs cargo-deny)
scripts/update-licenses.sh         # rewrites THIRD_PARTY_LICENSES.md from Cargo.lock (needs cargo-about); commit the result
```

The release stops if `THIRD_PARTY_LICENSES.md` is out of date, because the desktop programs ship with it. A license that `deny.toml` does not list is for a person to decide on: say in the pull request why it is fine.

The Linux packages and the one-line installer (`website/install.sh`) are tried in real Debian, Ubuntu, Fedora and Arch containers by `packaging/linux/tests/run.sh` and `installer.sh` (they need Docker; CI runs the first on every change). If you change either, run them.

The desktop app writes a log file (see the README) that must never contain what a person typed or copied: log the *kind* of a message, never its content.

To try the server without letting it move your real mouse, run it without input injection and with a scratch key directory:

```bash
cargo run -p telepad-server -- --no-input --key-dir /tmp/telepad-dev --pair
```

Some tests need the real operating system: the Linux `uinput` tests skip themselves when `/dev/uinput` cannot be opened (see the README's Linux notes to enable it), and the Windows cursor test is `#[ignore]`d because it needs an interactive desktop (`cargo test -p telepad-platform --test windows_send_input -- --ignored`).

### Android app

You need JDK 17 and the Android SDK (platform 35). Point Gradle at the SDK with `ANDROID_HOME` or an `sdk.dir=` line in `android/local.properties` (the file is ignored by git).

Gradle 8.10 cannot run on very new Java versions. If your default Java is one of those (on Java 27 it stops with just `What went wrong: 27`), run it with a JDK 17 instead: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest`.

```bash
cd android
./gradlew testDebugUnitTest      # logic, networking against a stand-in PC, every screen
./gradlew assembleDebug          # the APK: app/build/outputs/apk/debug/
./gradlew lintDebug              # Android lint
```

CI runs all three, and a lint error fails the build (warnings, such as a newer library being available, do not). No device or emulator is needed for any of that. The screen tests run on the JVM with Robolectric, and the networking tests talk to a stand-in PC on `localhost` that speaks the real protocol, with real encryption.

Things the tests cannot cover, so please try them on a phone if you change them: Bluetooth HID (many phones do not support the profile), the background-connection notification, the on-screen keyboard's behavior, haptics and discovery on a real network. [docs/manual-testing.md](docs/manual-testing.md) is the checklist maintainers run before a release. Say in your pull request what you tried it on.

#### Screenshots

Every screen has a picture in `android/app/src/test/screenshots`, rendered by `ui/ScreenshotTest.kt`. They are how a design change is reviewed (look at the diff of the images) and what the README shows.

```bash
./gradlew recordRoborazziDebug --tests '*ScreenshotTest'   # redraw them after a design change
./gradlew verifyRoborazziDebug                             # fail if a screen no longer matches its picture
```

Rendering can differ slightly between machines and operating systems, so CI does not compare pictures. Run `verifyRoborazziDebug` yourself before you record, to see that only the screens you meant to change are different.

## The website and the brand

The landing page is plain HTML, CSS and a little JavaScript in `website/`: no framework, no build step, and nothing loaded from other sites. It is served with a strict Content Security Policy (see `website/vercel.json`), so a stray inline style or external script will not work.

```bash
python3 website/tools/serve.py           # preview at http://127.0.0.1:8080, with the production headers
python3 website/tools/build_images.py    # after re-recording the app screenshots
docs/brand/export.sh                     # social images and icons, from their sources
python3 docs/brand/build_logo.py         # the logo files (needs the Cal Sans font)
```

Keep the copy honest: say only what the app and the server really do. If a claim is not something a test or the code backs up, leave it out.

## Writing Android code

**Put logic where it can be tested.** Everything that decides something (what a gesture means, what text to send, whether a PC is trusted, when to reconnect) is plain Kotlin under `core/`, with no Android types, and has unit tests. Composables and view models stay thin. If you find yourself wanting a device to test a rule, move the rule out.

**Text.** Every word the user can see or hear lives in `res/values/strings.xml`, never in code, in American English and in plain words (*Pair*, not *Initiate pairing sequence*). Use `plurals` for counts. A string says what happened and what to do next.

**Design.** The look comes from the theme, so that every accent and both light and dark stay consistent and readable:

- Colors: `MaterialTheme.colorScheme.*`, or `TelepadTheme.extended.*` for the app's own roles (success, warning, the pad). Never a literal `Color(0xFF…)` in a screen. The schemes are generated and tested for WCAG contrast; a hand-picked color is not.
- Spacing, shapes and type: `Spacing`, `TelepadShapes` and `MaterialTheme.typography`.
- Motion: through `Motion` and `rememberReducedMotion()`, so nothing moves for people who have turned animations off.
- Touch targets are at least 48 dp, and every control has a visible label or a content description. Check the screen with TalkBack.
- Look at your change in portrait, landscape, a tablet, light, dark and with a large font size. The fixtures in `ui/Fixtures.kt` make that quick.

**New screens and states** need three things: a fixture in `ui/Fixtures.kt`, a screenshot in `ScreenshotTest`, and an interaction test next to the others in `ui/screens/` that taps through it.

**Trust and pairing code** (`core/trust`, `connection/`, `core/crypto`) decides whether input from your phone reaches someone's computer. Changes there need tests for the failure cases (what happens when the key is different, when the handshake fails, when the user says no), not only the happy path. The rules, as they are today: a PC is remembered only after the person has confirmed its fingerprint and an encrypted connection has actually worked; a PC that answers with a different key is never trusted silently.

## Writing Rust code

- Keep `telepad-protocol` free of I/O, and `telepad-platform` the only place that touches the operating system.
- Anything that reads from the network is hostile input. Decoders return errors; they never panic, and lengths are checked before they are used.
- Platform code is easy to break on the systems you cannot run. Keep OS-specific decisions in small pure functions (see `os/linux/actions.rs` and `os/macos_plan.rs`) that are tested everywhere, with the actual system calls as thin as possible.
- Changes to the protocol add a new message tag; they do not change the meaning of an existing one, so that an old phone and a new PC (or the other way around) still understand each other.

## Pull requests

- Keep each one to a single change that can be described in a sentence. Separate refactors from behavior changes.
- Make sure the checks above pass for the half you touched. CI runs them again.
- Describe what you changed and why, and how you tested it. For anything visible, add the screenshots.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org): `fix: …`, `feat: …`, `docs: …`, `ci: …`, `refactor: …`, `test: …`, with a scope when it helps (`fix(server): …`).
- By contributing, you agree that your work is released under the project's [MIT License](LICENSE).

## Releases

Maintainers only. The Android app, the desktop server and the git tag all carry the same version, and the release workflow refuses to run if they differ.

A release is a build that has already been tried, with a tag on it. `.github/workflows/package.yml` builds everything that ships (the phone app, the Windows program and installer, the Linux packages, the Mac app and disk image) and tries each one: installed, run and removed, in real Debian, Ubuntu, Fedora and Arch, and on the Windows and Mac runners. CI calls it for every change to the code, and the Release workflow calls the same file, so a mistake in packaging fails a pull request and not release day. Release adds only what a tag is for: it checks that the tag matches the code and that CI passed on that commit, and it publishes.

```bash
scripts/bump-version.sh 2.1.0   # sets the version in Cargo.toml, build.gradle.kts and Cargo.lock, and starts a changelog section
# ... write the changelog section, commit ...
scripts/release.sh 2.1.0        # pushes main, waits for CI, tags, waits for the release, checks the downloads
```

`release.sh` asks before each step that cannot be undone (`--dry-run` shows what it would do). The changelog section for the version must be headed `## [2.1.0] - 2026-10-31` (version and date): the release workflow publishes it as the release notes, and the script dates it for you if it still says `Unreleased`.

The workflow builds the signed APK, the Windows installer, the macOS disk image, the Linux tarball and the console servers, and publishes them, each under a name with the version and a name without it (the website's download buttons use the latter), with a `SHA256SUMS` file and a build attestation for every file (see [docs/code-signing.md](docs/code-signing.md)). After it finishes, `release.sh` checks that every download link works and that the APK is signed with the same key as the previous release: a different key would stop phones from updating in place. The workflow needs the signing keystore in the repository's secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, and optionally `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`).
