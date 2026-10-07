# Packaging

How Telepad's desktop app becomes something a person can double-click. The release workflow (`.github/workflows/release.yml`) and CI (`.github/workflows/ci.yml`) both run these scripts, so what is tested is what ships. Signing is described in [docs/code-signing.md](../docs/code-signing.md).

| Folder | What it makes | Runs on |
| :--- | :--- | :--- |
| `windows/telepad.iss` | `telepad-<tag>-windows-x86_64-setup.exe`: an Inno Setup installer. Per user (no administrator), Start menu entry, an uninstaller in Settings → Apps, and an option to start at sign-in. | Windows (Inno Setup 6 is on GitHub's runners) |
| `macos/` | `Telepad.app` (`make-app.sh`), signed and notarized when Apple's credentials are present (`sign-and-notarize.sh`), in a disk image (`make-dmg.sh`) | `make-app.sh` anywhere; the others need a Mac |
| `linux/` | `telepad-<tag>-linux-x86_64.tar.gz` (`package.sh`): the program, its icons, a desktop entry and `install.sh`, which installs into `~/.local` and removes it again with `--uninstall` | Linux (GNU tar) |
| `icons/` | The icon in the formats each system wants, made from `docs/brand/icon.svg` by `make-icons.sh` | |

The Windows program itself gets its icon and version information (the name shown in Task Manager's list of startup apps, and in SmartScreen) from `crates/telepad-tray/build.rs`.

## Building one by hand

```bash
cargo build --release --bin telepad

# Linux
packaging/linux/package.sh telepad-dev-linux-x86_64 target/release/telepad dist
# macOS (the version is numbers only)
packaging/macos/make-app.sh 2.0.0 target/release/telepad dist/app
packaging/macos/sign-and-notarize.sh app dist/app/Telepad.app     # ad hoc without credentials
packaging/macos/make-dmg.sh telepad-dev-macos dist/app/Telepad.app dist
```

```powershell
# Windows: the installer, from the program that was just built
& "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe" "/DVersion=2.0.0" "/DSource=$PWD\target\release\telepad.exe" "/DOutputDir=$PWD\dist" "/DOutputName=telepad-dev-setup" packaging\windows\telepad.iss
```

After changing `docs/brand/icon.svg`, run `packaging/make-icons.sh` (needs `rsvg-convert`, `optipng` and Python with Pillow) and commit the results: the Windows build embeds `icons/telepad.ico`, and no build machine has the tools to make it.

## What each package does for the person

- **Starting it again opens it.** Telepad has no window, so starting it from the Start menu, Spotlight or the applications menu while it runs opens the page with the QR code. `telepad --quit` asks the running copy to quit; the Windows installer and `install.sh` use it to update a running copy.
- **Start at login** is the program's own setting (tray menu → *Start at login*): a `HKCU\...\Run` value on Windows, a LaunchAgent on macOS, an `~/.config/autostart` entry on Linux. It starts with `--background`, which keeps the page from opening by itself.
- **Uninstalling** removes the program, the Start menu or applications-menu entry and the start-at-login entry. The paired phones and the PC's identity key stay in the config folder (`%APPDATA%\Telepad`, `~/Library/Application Support/Telepad`, `~/.config/telepad`), so a reinstall keeps working with the phones that were paired.

## Package managers

People who use one expect `install` and `update` to just work, so the manifests are here, ready. After each release, `scripts/update-package-manifests.sh <tag>` fills in the version, the addresses and the checksums from the release's `SHA256SUMS`. Submitting is yours to do, because each needs your own account:

| Where | Files | How |
| :--- | :--- | :--- |
| **Arch Linux (AUR)** `telepad-bin` | `arch/PKGBUILD` | Make an account on aur.archlinux.org and add your SSH key. Then `git clone ssh://aur@aur.archlinux.org/telepad-bin.git`, copy `PKGBUILD` in, run `makepkg --printsrcinfo > .SRCINFO`, commit and push. It installs the program, the icon and entry, the licenses, and the udev rule and module list that make `/dev/uinput` work. Tried with `makepkg` against a built tarball. |
| **Windows: winget** | `winget/*.yaml` | Fork `microsoft/winget-pkgs`, put the three files in `manifests/o/omsingh02/Telepad/<version>/`, and open a pull request (or let `wingetcreate` do it). It is checked by a bot and a person; an unsigned installer may be asked about. |
| **Windows: Scoop** | `scoop/telepad.json` | Make a repository `scoop-telepad`, put the file in its `bucket/` folder, and people run `scoop bucket add telepad https://github.com/omsingh02/scoop-telepad`, then `scoop install telepad`. |
| **macOS: Homebrew** | `homebrew/telepad.rb` | Make a repository `homebrew-telepad`, put the file in `Casks/`, and people run `brew install --cask omsingh02/telepad/telepad`. The main Homebrew repository wants notarized apps, so a tap of your own is the way until then. |
| **Android: Obtainium** | none | Works now: people add the repository's address and Obtainium follows the releases. |
| **Android: IzzyOnDroid** | `../android/fastlane/` | Ask for the app in their issue tracker; it takes the APK from the GitHub release and the listing from `android/fastlane/metadata`. |
| **Android: F-Droid** | `../android/fastlane/` | A merge request to `fdroiddata` (their build recipe and a review). Telepad uses no Google services and only open source libraries; the one thing they may look at is `noise-java`, which comes from JitPack at a pinned commit. |

None of these changes what the apps do or say; they are other roads to the same files. The listing in `android/fastlane/metadata/android/en-US/` (title, descriptions, changelog named after the `versionCode`, icon and phone screenshots) is also what a store shows: update `changelogs/<versionCode>.txt` and the screenshots when a release changes what people see.
