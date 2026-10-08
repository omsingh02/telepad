//! What GitHub says about the releases, reduced to what an update needs, and trusting as little of it as can be.
//!
//! The list is the project's releases feed (`releases.atom`), which is an ordinary page of github.com and so is not
//! held to the small hourly allowance that GitHub's programming interface gives an address (60 requests, which a
//! campus or a mobile network shares between thousands of people). From it only one thing is read: which tags
//! exist. Everything else is rebuilt here: the page of a release and the address of each of its files come from the
//! tag and the file's name, never from the feed.

use crate::version::Version;

/// The project, as GitHub names it.
pub const REPO: &str = "omsingh02/telepad";

/// Where the releases are listed, and where their pages and files are. It is GitHub's, always; a development
/// build can point it at a server of its own on this computer to try the whole update from end to end.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Source {
    /// The feed of releases.
    pub list_url: String,
    /// The start of a release's file address; the tag and the file's name follow.
    pub download_base: String,
    /// The start of a release's page address; the tag follows.
    pub page_base: String,
}

impl Source {
    pub fn github() -> Self {
        Self {
            list_url: format!("https://github.com/{REPO}/releases.atom"),
            download_base: format!("https://github.com/{REPO}/releases/download"),
            page_base: format!("https://github.com/{REPO}/releases/tag"),
        }
    }

    /// A server at `origin` (such as `http://127.0.0.1:8000`) that answers like GitHub does, under `/list`,
    /// `/download/<tag>/<name>` and `/tag/<tag>`.
    pub fn local(origin: &str) -> Self {
        Self {
            list_url: format!("{origin}/list"),
            download_base: format!("{origin}/download"),
            page_base: format!("{origin}/tag"),
        }
    }

    /// Whether `url` is somewhere this source's list and files are fetched from.
    pub fn owns(&self, url: &str) -> bool {
        url == self.list_url || url.starts_with(&format!("{}/", self.download_base))
    }
}

/// A published release.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Release {
    pub version: Version,
    /// The git tag, such as `v2.0.0-alpha.4`.
    pub tag: String,
    /// Its page, for what changed and for downloading by hand.
    pub page: String,
}

/// A file attached to a release.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Asset {
    pub name: String,
    /// How big it is, when that is known (it is not, from the feed: the download says).
    pub size: u64,
    /// Where it is downloaded from.
    pub url: String,
}

impl Release {
    /// The release with this tag, or `None` if the tag is not one the project writes (`v` and a version number).
    pub fn for_tag(tag: &str, source: &Source) -> Option<Release> {
        let version: Version = tag.parse().ok()?;
        if tag != format!("v{version}") {
            return None;
        }
        Some(Release {
            page: format!("{}/{tag}", source.page_base),
            tag: tag.to_owned(),
            version,
        })
    }

    /// The file of this release called `name`, at its address. `name` must be a plain file name.
    pub fn asset(&self, name: &str, source: &Source) -> Option<Asset> {
        plain_name(name).then(|| Asset {
            name: name.to_owned(),
            size: 0,
            url: format!("{}/{}/{name}", source.download_base, self.tag),
        })
    }

    /// The list of checksums every release carries.
    pub fn sums(&self, source: &Source) -> Asset {
        self.asset("SHA256SUMS", source).expect("a plain name")
    }
}

/// A file name that is only a name: nothing that would put a download anywhere but where it is told to go.
fn plain_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 200
        && !name.starts_with('.')
        && name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'_'))
}

/// Reads the feed. The tag of each release is taken from the link to its page, and only a tag that is a version
/// number as the project writes them counts; anything else in the feed is left out, not an error. Returns `None`
/// when `feed` is not a feed at all.
pub fn parse(feed: &str, source: &Source) -> Option<Vec<Release>> {
    if !feed.contains("<feed") {
        return None;
    }
    // What a release says about itself is written into the feed as escaped text, so it cannot add an entry or a
    // link; but the notes are of no interest here, and are cut out before looking.
    let page = format!("href=\"{}/", source.page_base);
    let mut releases = Vec::new();
    for entry in without_content(feed).split("<entry>").skip(1) {
        let entry = entry.split("</entry>").next().unwrap_or("");
        let Some(at) = entry.find(&page) else {
            continue;
        };
        let tag = entry[at + page.len()..].split('"').next().unwrap_or("");
        if let Some(release) = Release::for_tag(tag, source) {
            releases.push(release);
        }
    }
    Some(releases)
}

/// `text` without anything between `<content` and `</content>`.
fn without_content(text: &str) -> String {
    let mut out = String::with_capacity(text.len() / 4);
    let mut rest = text;
    while let Some(start) = rest.find("<content") {
        out.push_str(&rest[..start]);
        rest = match rest[start..].find("</content>") {
            Some(end) => &rest[start + end + "</content>".len()..],
            None => "",
        };
    }
    out.push_str(rest);
    out
}

/// The latest release that `current` should hear about, if there is one.
pub fn newest_for<'a>(releases: &'a [Release], current: &Version) -> Option<&'a Release> {
    releases
        .iter()
        .filter(|release| current.is_offered(&release.version))
        .max_by(|a, b| a.version.cmp(&b.version))
}

/// A feed with these tags, in the shape GitHub writes it, for tests.
#[cfg(test)]
pub(crate) fn feed_of(tags: &[&str]) -> String {
    let entries: String = tags
        .iter()
        .map(|tag| {
            format!(
                "<entry>\n<id>tag:github.com,2008:Repository/1/{tag}</id>\n<updated>2026-10-07T22:39:05Z</updated>\n\
                 <link rel=\"alternate\" type=\"text/html\" href=\"https://github.com/omsingh02/telepad/releases/tag/{tag}\"/>\n\
                 <title>Telepad {tag}</title>\n<content type=\"html\">&lt;p&gt;notes&lt;/p&gt;</content>\n</entry>\n"
            )
        })
        .collect();
    format!(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<feed xmlns=\"http://www.w3.org/2005/Atom\">\n\
         <id>tag:github.com,2008:https://github.com/omsingh02/telepad/releases</id>\n\
         <link type=\"text/html\" rel=\"alternate\" href=\"https://github.com/omsingh02/telepad/releases\"/>\n\
         <title>Release notes from telepad</title>\n{entries}</feed>\n"
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v(text: &str) -> Version {
        text.parse().unwrap()
    }

    #[test]
    fn it_reads_the_tags_of_the_releases_in_the_feed() {
        let feed = feed_of(&["v2.0.0-alpha.4", "v2.0.0-alpha.3", "v1.0.1"]);
        let tags: Vec<_> = parse(&feed, &Source::github())
            .unwrap()
            .into_iter()
            .map(|r| r.tag)
            .collect();
        assert_eq!(tags, ["v2.0.0-alpha.4", "v2.0.0-alpha.3", "v1.0.1"]);
    }

    #[test]
    fn it_reads_the_real_feed_shape() {
        // What GitHub writes today for this project, abridged: the first release's notes hold escaped markup.
        let feed = r#"<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/" xml:lang="en-US">
  <id>tag:github.com,2008:https://github.com/omsingh02/telepad/releases</id>
  <link type="text/html" rel="alternate" href="https://github.com/omsingh02/telepad/releases"/>
  <link type="application/atom+xml" rel="self" href="https://github.com/omsingh02/telepad/releases.atom"/>
  <title>Release notes from telepad</title>
  <updated>2026-10-07T22:32:44Z</updated>
  <entry>
    <id>tag:github.com,2008:Repository/1216931592/v2.0.0-alpha.3</id>
    <updated>2026-10-07T22:39:05Z</updated>
    <link rel="alternate" type="text/html" href="https://github.com/omsingh02/telepad/releases/tag/v2.0.0-alpha.3"/>
    <title>Telepad v2.0.0-alpha.3</title>
    <content type="html">&lt;p&gt;A new desktop app&lt;/p&gt;
&lt;li&gt;&lt;a href=&quot;https://github.com/omsingh02/telepad/releases/tag/v9.9.9&quot;&gt;x&lt;/a&gt;&lt;/li&gt;</content>
    <author><name>omsingh02</name></author>
  </entry>
</feed>"#;
        let releases = parse(feed, &Source::github()).unwrap();
        assert_eq!(releases.len(), 1);
        assert_eq!(releases[0].tag, "v2.0.0-alpha.3");
        assert_eq!(
            releases[0].page,
            "https://github.com/omsingh02/telepad/releases/tag/v2.0.0-alpha.3"
        );
    }

    #[test]
    fn what_the_notes_say_cannot_add_a_release() {
        // Even an entry written out in a release's notes (escaped, as GitHub does) is not an entry.
        let evil = "<entry><link rel=\"alternate\" href=\"https://github.com/omsingh02/telepad/releases/tag/v9.9.9\"/></entry>";
        let feed = feed_of(&["v2.0.0-alpha.3"])
            .replace("notes", &evil.replace('<', "&lt;").replace('>', "&gt;"));
        assert_eq!(parse(&feed, &Source::github()).unwrap().len(), 1);
        // And one that is not escaped sits inside <content>, which is cut out before looking.
        let raw = feed_of(&["v2.0.0-alpha.3"]).replace("&lt;p&gt;notes&lt;/p&gt;", evil);
        let tags: Vec<_> = parse(&raw, &Source::github())
            .unwrap()
            .into_iter()
            .map(|r| r.tag)
            .collect();
        assert_eq!(tags, ["v2.0.0-alpha.3"]);
    }

    #[test]
    fn tags_that_are_not_versions_are_left_out_and_so_are_links_to_elsewhere() {
        let feed = feed_of(&[
            "nightly",
            "2.0.0-alpha.2",
            "v2.0.0-alpha.1",
            "v1.0",
            "v2.0.0-alpha.3",
        ]);
        let tags: Vec<_> = parse(&feed, &Source::github())
            .unwrap()
            .into_iter()
            .map(|r| r.tag)
            .collect();
        assert_eq!(tags, ["v2.0.0-alpha.1", "v2.0.0-alpha.3"]);
        let elsewhere =
            feed_of(&["v2.0.0"]).replace("github.com/omsingh02/telepad", "evil.example/x");
        assert!(parse(&elsewhere, &Source::github()).unwrap().is_empty());
    }

    #[test]
    fn addresses_are_made_here_from_the_tag_and_the_name_and_never_copied() {
        let source = Source::github();
        let release = Release::for_tag("v2.0.0-alpha.4", &source).unwrap();
        assert_eq!(
            release.page,
            "https://github.com/omsingh02/telepad/releases/tag/v2.0.0-alpha.4"
        );
        let deb = release
            .asset("telepad-v2.0.0-alpha.4-linux-x86_64.deb", &source)
            .unwrap();
        assert_eq!(
            deb.url,
            "https://github.com/omsingh02/telepad/releases/download/v2.0.0-alpha.4/telepad-v2.0.0-alpha.4-linux-x86_64.deb"
        );
        assert_eq!(release.sums(&source).name, "SHA256SUMS");
    }

    #[test]
    fn a_file_whose_name_could_lead_out_of_the_download_folder_is_refused() {
        let source = Source::github();
        let release = Release::for_tag("v2.0.0", &source).unwrap();
        for name in [
            "../../etc/passwd",
            ".hidden",
            "has space.apk",
            "a/b",
            "",
            "a\\b",
        ] {
            assert_eq!(release.asset(name, &source), None, "{name:?}");
        }
    }

    #[test]
    fn it_picks_the_newest_that_the_person_should_hear_about() {
        let feed = feed_of(&["v2.0.0-alpha.4", "v2.0.0-alpha.3", "v1.0.1"]);
        let releases = parse(&feed, &Source::github()).unwrap();
        let tag = |current: &str| newest_for(&releases, &v(current)).map(|r| r.tag.as_str());
        assert_eq!(tag("2.0.0-alpha.3"), Some("v2.0.0-alpha.4"));
        assert_eq!(tag("2.0.0-alpha.1"), Some("v2.0.0-alpha.4"));
        assert_eq!(tag("2.0.0-alpha.4"), None, "already on it");
        assert_eq!(
            tag("2.0.0"),
            None,
            "a release is not moved back onto alphas"
        );
        assert_eq!(
            tag("1.0.0"),
            Some("v1.0.1"),
            "an old release hears about releases only"
        );
    }

    #[test]
    fn what_is_not_a_feed_is_none_and_an_empty_feed_is_not() {
        assert_eq!(parse("<html>rate limited</html>", &Source::github()), None);
        assert_eq!(
            parse("{\"message\":\"Not Found\"}", &Source::github()),
            None
        );
        assert_eq!(parse(&feed_of(&[]), &Source::github()).unwrap(), []);
    }

    #[test]
    fn a_local_source_builds_addresses_on_its_own_server_and_owns_only_those() {
        let source = Source::local("http://127.0.0.1:8000");
        let release = Release::for_tag("v2.0.0-alpha.4", &source).unwrap();
        assert_eq!(release.page, "http://127.0.0.1:8000/tag/v2.0.0-alpha.4");
        assert_eq!(
            release.sums(&source).url,
            "http://127.0.0.1:8000/download/v2.0.0-alpha.4/SHA256SUMS"
        );
        assert!(source.owns(&source.list_url));
        assert!(source.owns("http://127.0.0.1:8000/download/v2.0.0-alpha.4/x"));
        assert!(!source.owns("https://github.com/omsingh02/telepad/releases/download/v1/x"));
    }

    #[test]
    fn github_owns_this_projects_feed_and_downloads_and_nothing_near_them() {
        let github = Source::github();
        assert!(github.owns(&github.list_url));
        assert!(github.owns("https://github.com/omsingh02/telepad/releases/download/v2.0.0/x.deb"));
        for url in [
            "https://evil.example/telepad",
            "http://github.com/omsingh02/telepad/releases.atom",
            "https://github.com/someone-else/telepad/releases.atom",
            "https://github.com/omsingh02/telepad/archive/main.zip",
            "https://github.com.evil.example/omsingh02/telepad/releases/download/v1/x",
            "https://api.github.com/repos/omsingh02/telepad/releases",
            "file:///etc/passwd",
        ] {
            assert!(!github.owns(url), "{url}");
        }
    }
}
