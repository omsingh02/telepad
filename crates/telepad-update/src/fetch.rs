//! Asking whether there is a newer release, and fetching its file for this computer, checked.

use crate::kind::Kind;
use crate::net::{Http, NetError};
use crate::release::{self, Asset, Release, Source};
use crate::sums::{self, Sums};
use crate::version::Version;
use std::fs::File;
use std::io::Write;
use std::path::{Path, PathBuf};

/// GitHub's list of releases is a few tens of kilobytes; this is only a stop for a runaway answer.
const LIST_LIMIT: u64 = 4 * 1024 * 1024;
const SUMS_LIMIT: u64 = 256 * 1024;
/// The largest file that is ever fetched: the biggest download is under ten megabytes, and this is far above it
/// and far below a full disk.
const FILE_LIMIT: u64 = 400 * 1024 * 1024;

/// What went wrong, in words for the person.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum Error {
    #[error("{0}")]
    Net(#[from] NetError),
    #[error("GitHub answered with something other than its list of releases.")]
    NotAList,
    #[error("The release has no checksum for {0}, so it was not trusted.")]
    NoChecksum(String),
    #[error("{0} did not match its published checksum, so it was thrown away. Try again; if it happens again, download it from the release page.")]
    ChecksumMismatch(String),
    #[error("Telepad could not save the download: {0}")]
    Disk(String),
}

/// What is to be had.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outcome {
    /// Nothing newer that this person should hear about.
    UpToDate,
    Available(Box<Available>),
}

/// A newer release, and how to get it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Available {
    pub release: Release,
    /// What Telepad installs it with, when this copy can update itself and the release has the file.
    pub install: Option<Install>,
    /// When it cannot, why not, in words for the person.
    pub by_hand: Option<String>,
}

/// The file to fetch, and the list of checksums that vouches for it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Install {
    pub asset: Asset,
    pub sums: Asset,
}

/// Asks `source` for the releases and says whether one is newer than `current`, and whether this copy can install it.
pub fn check(
    http: &dyn Http,
    source: &Source,
    current: &Version,
    kind: &Kind,
) -> Result<Outcome, Error> {
    let text = http.get_text(&source.list_url, LIST_LIMIT)?;
    let releases = release::parse(&text, source).ok_or(Error::NotAList)?;
    let Some(release) = release::newest_for(&releases, current) else {
        return Ok(Outcome::UpToDate);
    };
    let (install, by_hand) = match kind {
        Kind::Unsupported(why) => (None, Some(why.clone())),
        kind => match install_for(http, source, release, kind)? {
            Some(install) => (Some(install), None),
            None => (
                None,
                Some("This release does not have a file for this computer yet.".to_owned()),
            ),
        },
    };
    Ok(Outcome::Available(Box::new(Available {
        release: release.clone(),
        install,
        by_hand,
    })))
}

/// The file that installs `release` on this kind of computer, if the release's own checksum list says it has
/// one: the list is what vouches for a file, so a file it does not name is not one to install.
fn install_for(
    http: &dyn Http,
    source: &Source,
    release: &Release,
    kind: &Kind,
) -> Result<Option<Install>, Error> {
    let Some(asset) = kind
        .asset_name(&release.version.to_string())
        .and_then(|name| release.asset(&name, source))
    else {
        return Ok(None);
    };
    let sums = release.sums(source);
    let listed = match http.get_text(&sums.url, SUMS_LIMIT) {
        Ok(text) => text,
        // A release whose files are not up yet, or that has no checksums: nothing to install from.
        Err(NetError::Status(404)) => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    Ok(Sums::parse(&listed)
        .get(&asset.name)
        .map(|_| Install { asset, sums }))
}

/// A file that has been downloaded and matches its published checksum.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Downloaded {
    pub path: PathBuf,
    /// Its SHA-256 as 64 hex digits, for whatever installs it to check again at the last moment.
    pub sha256: String,
}

/// Downloads `install.asset` into `dir` and checks it against the release's `SHA256SUMS`. A file that does not
/// match is deleted and is not returned. `dir` must exist, and should be the person's alone.
pub fn download(
    http: &dyn Http,
    install: &Install,
    dir: &Path,
    progress: &mut dyn FnMut(u64, Option<u64>),
) -> Result<Downloaded, Error> {
    let listed = http.get_text(&install.sums.url, SUMS_LIMIT)?;
    let expected = Sums::parse(&listed)
        .get(&install.asset.name)
        .ok_or_else(|| Error::NoChecksum(install.asset.name.clone()))?;

    let path = dir.join(&install.asset.name);
    let disk = |error: std::io::Error| Error::Disk(error.to_string());
    let mut file = create_private(&path).map_err(disk)?;
    let limit = if install.asset.size > 0 {
        install.asset.size.min(FILE_LIMIT)
    } else {
        FILE_LIMIT
    };
    let fetched = http
        .download(&install.asset.url, limit, &mut file, progress)
        .map_err(Error::from);
    let flushed = file.flush().and_then(|()| file.sync_all()).map_err(disk);
    drop(file);
    if let Err(error) = fetched.and(flushed) {
        let _ = std::fs::remove_file(&path);
        return Err(error);
    }

    let actual = File::open(&path)
        .and_then(sums::hash_reader)
        .map_err(disk)?;
    if actual != expected {
        let _ = std::fs::remove_file(&path);
        return Err(Error::ChecksumMismatch(install.asset.name.clone()));
    }
    Ok(Downloaded {
        path,
        sha256: sums::to_hex(&actual),
    })
}

/// A new file that only its owner can read or change.
fn create_private(path: &Path) -> std::io::Result<File> {
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(path)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::kind::PackageFormat;
    use crate::net::fake::FakeHttp;

    const LIST: &str = "https://feed.example/releases.atom";
    const BASE: &str = "https://github.com/omsingh02/telepad/releases/download";

    /// GitHub's addresses for files, with the feed somewhere the fake network has it.
    fn source() -> Source {
        Source {
            list_url: LIST.into(),
            ..Source::github()
        }
    }

    fn v(text: &str) -> Version {
        text.parse().unwrap()
    }

    /// A network with a feed of these tags, and the checksum list of `tag` naming these files.
    fn network(tags: &[&str], tag: &str, files: Option<&[&str]>) -> FakeHttp {
        let http = FakeHttp::default().with(LIST, release::feed_of(tags));
        match files {
            Some(files) => {
                let sums: String = files
                    .iter()
                    .map(|name| format!("{}  {name}\n", "0".repeat(64)))
                    .collect();
                http.with(&format!("{BASE}/{tag}/SHA256SUMS"), sums)
            }
            None => http, // no SHA256SUMS: it answers 404
        }
    }

    fn deb() -> Kind {
        Kind::LinuxPackage(PackageFormat::Deb)
    }

    #[test]
    fn a_newer_release_comes_with_the_file_that_installs_it_on_this_kind_of_computer() {
        let http = network(
            &["v2.0.0-alpha.4", "v2.0.0-alpha.3"],
            "v2.0.0-alpha.4",
            Some(&[
                "telepad-v2.0.0-alpha.4-linux-x86_64.deb",
                "telepad-v2.0.0-alpha.4-linux-x86_64.rpm",
            ]),
        );
        let Outcome::Available(found) =
            check(&http, &source(), &v("2.0.0-alpha.3"), &deb()).unwrap()
        else {
            panic!("there is an update");
        };
        assert_eq!(found.release.tag, "v2.0.0-alpha.4");
        let install = found.install.unwrap();
        assert_eq!(
            install.asset.url,
            format!("{BASE}/v2.0.0-alpha.4/telepad-v2.0.0-alpha.4-linux-x86_64.deb")
        );
        assert_eq!(
            install.sums.url,
            format!("{BASE}/v2.0.0-alpha.4/SHA256SUMS")
        );
        assert_eq!(found.by_hand, None);
    }

    #[test]
    fn nothing_newer_means_up_to_date_and_nothing_else_is_fetched() {
        let http = network(&["v2.0.0-alpha.3"], "v2.0.0-alpha.3", None);
        assert_eq!(
            check(&http, &source(), &v("2.0.0-alpha.3"), &deb()).unwrap(),
            Outcome::UpToDate
        );
        assert_eq!(
            check(&http, &source(), &v("2.0.0"), &deb()).unwrap(),
            Outcome::UpToDate
        );
        assert!(http.asked.lock().unwrap().iter().all(|url| url == LIST));
    }

    #[test]
    fn a_copy_that_cannot_update_itself_is_still_told_and_pointed_to_the_page() {
        let http = network(&["v2.0.0-alpha.4"], "v2.0.0-alpha.4", None);
        let kind = Kind::Unsupported("Homebrew installed Telepad.".into());
        let Outcome::Available(found) =
            check(&http, &source(), &v("2.0.0-alpha.3"), &kind).unwrap()
        else {
            panic!();
        };
        assert_eq!(found.install, None);
        assert_eq!(
            found.by_hand.as_deref(),
            Some("Homebrew installed Telepad.")
        );
        assert!(found.release.page.ends_with("/releases/tag/v2.0.0-alpha.4"));
        assert_eq!(
            http.asked.lock().unwrap().len(),
            1,
            "only the feed was fetched"
        );
    }

    #[test]
    fn a_release_whose_checksums_do_not_name_the_file_or_are_missing_is_not_installed_from() {
        let no_file = network(
            &["v2.0.0-alpha.4"],
            "v2.0.0-alpha.4",
            Some(&["telepad-v2.0.0-alpha.4-linux-x86_64.rpm"]),
        );
        let no_sums = network(&["v2.0.0-alpha.4"], "v2.0.0-alpha.4", None);
        for http in [no_file, no_sums] {
            let Outcome::Available(found) =
                check(&http, &source(), &v("2.0.0-alpha.3"), &deb()).unwrap()
            else {
                panic!();
            };
            assert_eq!(found.install, None);
            assert!(found
                .by_hand
                .unwrap()
                .contains("does not have a file for this computer"));
        }
    }

    #[test]
    fn what_goes_wrong_on_the_way_is_told() {
        let offline = FakeHttp::default().failing(LIST, NetError::Unreachable);
        assert_eq!(
            check(&offline, &source(), &v("2.0.0"), &deb()).unwrap_err(),
            Error::Net(NetError::Unreachable)
        );
        let html = FakeHttp::default().with(LIST, "<html>Service Unavailable</html>");
        assert_eq!(
            check(&html, &source(), &v("2.0.0"), &deb()).unwrap_err(),
            Error::NotAList
        );
        // The checksum list failing for a reason other than being absent is a failure, not "no file".
        let limited = network(&["v2.0.0-alpha.4"], "v2.0.0-alpha.4", None).failing(
            &format!("{BASE}/v2.0.0-alpha.4/SHA256SUMS"),
            NetError::Unreachable,
        );
        assert_eq!(
            check(&limited, &source(), &v("2.0.0-alpha.3"), &deb()).unwrap_err(),
            Error::Net(NetError::Unreachable)
        );
    }

    // ── Downloading ──────────────────────────────────────────────────

    const BODY: &[u8] = b"pretend this is a package";

    fn install(sums_url: &str, file_url: &str) -> Install {
        Install {
            asset: Asset {
                name: "telepad.deb".into(),
                size: BODY.len() as u64,
                url: file_url.into(),
            },
            sums: Asset {
                name: "SHA256SUMS".into(),
                size: 100,
                url: sums_url.into(),
            },
        }
    }

    fn temp(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("telepad-fetch-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn sums_for(body: &[u8]) -> String {
        format!(
            "{}  telepad.deb\n",
            sums::to_hex(&sums::hash_reader(body).unwrap())
        )
    }

    #[test]
    fn a_file_that_matches_its_checksum_is_kept() {
        let dir = temp("good");
        let http = FakeHttp::default()
            .with("https://s", sums_for(BODY))
            .with("https://f", BODY);
        let mut seen = 0;
        let got = download(
            &http,
            &install("https://s", "https://f"),
            &dir,
            &mut |done, _| seen += done,
        )
        .unwrap();
        let path = got.path.clone();
        assert_eq!(got.sha256, sums::to_hex(&sums::hash_reader(BODY).unwrap()));
        assert_eq!(seen, BODY.len() as u64, "the pieces add up to the file");
        assert_eq!(std::fs::read(&path).unwrap(), BODY);
        assert_eq!(path, dir.join("telepad.deb"));
        assert!(seen > 0, "progress was reported");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                std::fs::metadata(&path).unwrap().permissions().mode() & 0o777,
                0o600
            );
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_file_that_does_not_match_is_deleted_and_never_handed_over() {
        let dir = temp("bad");
        let run = |served: &[u8]| {
            let http = FakeHttp::default()
                .with("https://s", sums_for(BODY))
                .with("https://f", served);
            download(
                &http,
                &install("https://s", "https://f"),
                &dir,
                &mut |_, _| {},
            )
        };

        // The same length, different bytes.
        assert_eq!(
            run(b"PRETEND THIS IS A PACKAGE").unwrap_err(),
            Error::ChecksumMismatch("telepad.deb".into())
        );
        assert!(!dir.join("telepad.deb").exists());

        // Bigger than the release says it is: it cannot be the file, and is refused before it is all fetched.
        assert_eq!(
            run(b"pretend this is a package with more").unwrap_err(),
            Error::Net(NetError::TooLarge)
        );
        assert!(!dir.join("telepad.deb").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn without_a_checksum_for_the_file_nothing_is_even_downloaded() {
        let dir = temp("nosum");
        let http = FakeHttp::default()
            .with(
                "https://s",
                "0000000000000000000000000000000000000000000000000000000000000000  other.deb\n",
            )
            .with("https://f", BODY);
        let error = download(
            &http,
            &install("https://s", "https://f"),
            &dir,
            &mut |_, _| {},
        )
        .unwrap_err();
        assert_eq!(error, Error::NoChecksum("telepad.deb".into()));
        assert!(
            !http.asked.lock().unwrap().contains(&"https://f".to_owned()),
            "the file was never asked for"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_failed_download_leaves_nothing_behind() {
        let dir = temp("fail");
        let http = FakeHttp::default()
            .with("https://s", sums_for(BODY))
            .failing("https://f", NetError::Unreachable);
        let error = download(
            &http,
            &install("https://s", "https://f"),
            &dir,
            &mut |_, _| {},
        )
        .unwrap_err();
        assert_eq!(error, Error::Net(NetError::Unreachable));
        assert!(!dir.join("telepad.deb").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
