# Code signing

Operating systems warn about programs they cannot tie to a known publisher: Windows shows *Windows protected your PC* (SmartScreen), macOS says it *cannot verify* the app, and Android's Play Protect asks before an app from outside the Play Store installs. A signed program shows who made it, and a notarized Mac app opens without a question. This page says what is signed today, what is not, and exactly what to set up to change that. Nothing here is needed to build or run Telepad.

## Where things stand

| File | Signed today | Effect |
| :--- | :--- | :--- |
| Android APK | **Yes**, with the project's release key | Phones can update the app in place, and a tampered APK is refused. Play Protect may still ask, because the app is not from the Play Store. |
| Windows installer and `.exe` | No | SmartScreen: *More info → Run anyway* |
| macOS `Telepad.app` and `.dmg` | Ad hoc only (what an Apple silicon Mac needs to run it at all) | Gatekeeper: *System Settings → Privacy & Security → Open Anyway*. The Accessibility permission usually has to be given again after an update. |
| Linux tarball | No (Linux has no such gatekeeper) | None |
| **Every file** | **A build attestation** and a SHA-256 in `SHA256SUMS` | Proves *which workflow run, from which commit,* built the file (see below) |

The release notes say, for each of Windows and macOS, whether that build was signed, and show the warning text only when it was not.

### Checking a download today

```bash
sha256sum -c SHA256SUMS --ignore-missing
gh attestation verify telepad-windows-x86_64-setup.exe --repo omsingh02/telepad
```

`gh attestation verify` checks a signature made by GitHub's own build system (Sigstore) over the file's digest: the file is the one that the `Release` workflow of this repository built, from the tagged commit. It does not make Windows or macOS stop warning; only the steps below do that.

## Turning signing on

The release workflow already contains the signing steps. Each switches on by itself when its secrets exist, and is skipped when they do not, so adding them is the whole change.

### Windows: SignPath Foundation (free for open source)

[SignPath Foundation](https://signpath.org/) signs open source projects for free, with a certificate in the Foundation's name (the publisher shows as *SignPath Foundation*). Check their current terms; at the time of writing a project needs an OSI-approved licence, to be released and actively maintained, to say how it signs, and its maintainers to use two-factor sign-in.

1. **Apply** through the *Apply* link at <https://signpath.org/> for `omsingh02/telepad`. Once the project is approved, SignPath creates an organization for it.
2. **In SignPath** (the web page of that organization): create a project with the slug `telepad`; add the GitHub repository as a *trusted build system* (SignPath explains the GitHub connector there); create two *artifact configurations* and one *signing policy*:
   - artifact configuration `programs`, for the program files:
     ```xml
     <artifact-configuration xmlns="http://signpath.io/artifact-configuration/v1">
       <zip-file>
         <pe-file path="telepad.exe"><authenticode-sign/></pe-file>
         <pe-file path="telepad-server.exe"><authenticode-sign/></pe-file>
       </zip-file>
     </artifact-configuration>
     ```
   - artifact configuration `installer`, for the installer built from them:
     ```xml
     <artifact-configuration xmlns="http://signpath.io/artifact-configuration/v1">
       <zip-file>
         <pe-file path="*-setup.exe"><authenticode-sign/></pe-file>
       </zip-file>
     </artifact-configuration>
     ```
   - signing policy `release-signing`. For the Foundation's certificate it needs a person's approval for each release; that is the SignPath email in step 4.
3. **In GitHub** (repository → Settings → Secrets and variables → Actions):

   | Kind | Name | Value |
   | :--- | :--- | :--- |
   | Secret | `SIGNPATH_API_TOKEN` | an API token of a SignPath user who may submit for this project |
   | Variable | `SIGNPATH_ORGANIZATION_ID` | the organization's id (it is in the address of SignPath's pages for the organization) |
   | Variable (optional) | `SIGNPATH_PROJECT_SLUG` | only if it is not `telepad` |
   | Variable (optional) | `SIGNPATH_SIGNING_POLICY_SLUG` | only if it is not `release-signing` |

4. **Tag a release.** The workflow submits the programs, waits for approval (an email from SignPath; up to an hour), builds the installer from the signed programs, submits that, and publishes. The release notes then leave out the Windows warning text.
5. **Tell people** how it is signed. SignPath Foundation requires a *code signing policy* on the project's download page (attribution, who the committers, reviewers and approvers are, and a privacy statement); this is the text for the README, to add *after* the application is approved:

   > **Code signing policy.** Free code signing for Windows builds is provided by [SignPath.io](https://signpath.io), certificate by [SignPath Foundation](https://signpath.org). Every signed file is built by the [Release workflow](.github/workflows/release.yml) on GitHub-hosted runners from a tagged commit of this repository.
   >
   > - **Committers and reviewers:** [Om Singh](https://github.com/omsingh02) and any contributor whose pull request is merged (see the [pull requests](https://github.com/omsingh02/telepad/pulls)).
   > - **Approvers:** [Om Singh](https://github.com/omsingh02). Each release is approved by hand in SignPath before it is published.
   >
   > **Privacy policy.** This program will not transfer any information to other networked systems unless specifically requested by the user or the person installing or operating it. Telepad sends what you type and do only to the phone or PC you paired it with, over your own network, and has no accounts, analytics or ads.

Other ways to sign on Windows, if SignPath does not suit: Microsoft's **Azure Trusted Signing** (a small monthly fee; check which countries and kinds of publisher it is open to), or an **OV/EV certificate** from a certificate authority with a cloud signing service (since 2023 the key must live in hardware, so a file in a secret is not enough). Both would replace the two SignPath steps in `.github/workflows/release.yml` with their own sign step.

SmartScreen also weighs *reputation*: a newly signed program can still show a warning for a while until enough people have run it. That fades; it does not need anything more from you.

### macOS: Apple Developer ID and notarization

1. **Join the Apple Developer Program** (paid, yearly) at <https://developer.apple.com/programs/>.
2. **Make a certificate:** in *Certificates, Identifiers & Profiles*, create a **Developer ID Application** certificate (Xcode or Keychain Access make the signing request). Export it from Keychain Access as a `.p12` with a password.
3. **Make an API key for notarization:** App Store Connect → *Users and Access → Integrations → App Store Connect API*: create a key with the *Developer* role, download the `.p8` (once only), and note its **Key ID** and the **Issuer ID** at the top of the page.
4. **In GitHub** (Settings → Secrets and variables → Actions) add these secrets:

   | Secret | Value |
   | :--- | :--- |
   | `APPLE_CERTIFICATE_BASE64` | `base64 -i certificate.p12` (the whole output) |
   | `APPLE_CERTIFICATE_PASSWORD` | the password of the `.p12` |
   | `APPLE_SIGNING_IDENTITY` | the certificate's name, as `security find-identity -v -p codesigning` shows it: `Developer ID Application: Om Singh (ABCDE12345)` |
   | `APPLE_API_KEY_BASE64` | `base64 -i AuthKey_XXXXXXXXXX.p8` |
   | `APPLE_API_KEY_ID` | the key's id |
   | `APPLE_API_ISSUER_ID` | the issuer id |

5. **Tag a release.** `packaging/macos/sign-and-notarize.sh` signs the app with the hardened runtime, sends it to Apple, waits for *Accepted*, and staples the ticket; the disk image is signed, notarized and stapled too. The app then opens with no question, and the Accessibility permission survives updates. The release notes leave out the macOS warning text.

   The script can be tried on a Mac before a release: put the same names in the environment and run `packaging/macos/make-app.sh`, `sign-and-notarize.sh app`, `make-dmg.sh` by hand (see [`packaging/README.md`](../packaging/README.md)).

### Android

The APK is already signed with the release key kept in the repository's secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`). **Keep a safe copy of that keystore and its passwords outside GitHub:** an app signed with a different key cannot update the installed one, so losing it means everyone has to uninstall first. `scripts/release.sh` compares each release's signing key with the previous release's and warns if they differ.

Play Protect's prompt for apps from outside the Play Store goes away only by publishing there (a one-time fee and a review); it is not something signing the APK changes.

## Checking that signing worked

```powershell
# Windows
Get-AuthenticodeSignature .\telepad-windows-x86_64-setup.exe | Format-List Status, SignerCertificate
```
```bash
# macOS
spctl --assess --type open --context context:primary-signature -vv telepad-macos-universal.dmg
codesign --verify --deep --strict --verbose=2 /Applications/Telepad.app
xcrun stapler validate /Applications/Telepad.app
```

## What the workflow does without any of this

It builds everything, signs the APK, gives every file a build attestation and a checksum, and says in the release notes which warnings to expect. Signing is purely additive: no step fails because a secret is missing.

## Updates, and what signing would add

The apps update themselves (see *Updates* in the README), and what protects that is: the program fetches only from this repository's GitHub releases over HTTPS; it installs nothing whose SHA-256 differs from the `SHA256SUMS` published with the release (on Linux the check is repeated by the privileged step, on the copy that is installed); and Android refuses an APK that is not signed with the app's own key. What that cannot cover is a release that is bad when it is *published*: the checksums and the files come from the same place. Code signing closes part of that gap on Windows and macOS (the system checks the publisher's signature when the installer or the app runs, and the updater can insist on it), and a signature over `SHA256SUMS` made with a key that is not kept in GitHub would close the rest. Neither is done yet; both are additive, and the updater needs no change to benefit from the first.
