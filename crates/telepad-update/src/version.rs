//! Release numbers, as the tags carry them (`v2.0.0`, `v2.0.0-alpha.4`), and which one is newer.
//!
//! This is semantic versioning, written out here because the rule that matters is a small part of it:
//! `2.0.0-alpha.4` comes after `2.0.0-alpha.3` and before `2.0.0`.

use std::cmp::Ordering;
use std::fmt;
use std::str::FromStr;

/// A release number.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Version {
    major: u64,
    minor: u64,
    patch: u64,
    /// The dot-separated parts after the hyphen (`alpha`, `4`); empty for a final release.
    pre: Vec<Part>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum Part {
    Number(u64),
    Text(String),
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("\"{0}\" is not a version number like 2.0.0 or 2.0.0-alpha.4")]
pub struct ParseError(String);

impl Version {
    /// Whether this is a pre-release (an alpha, a beta, a release candidate).
    pub fn is_prerelease(&self) -> bool {
        !self.pre.is_empty()
    }

    /// Whether a person on `self` should be offered `newer`: it has to be later, and only someone already
    /// on a pre-release is offered another pre-release. A person on a final release hears about final ones.
    pub fn is_offered(&self, newer: &Version) -> bool {
        newer > self && (!newer.is_prerelease() || self.is_prerelease())
    }
}

impl FromStr for Version {
    type Err = ParseError;

    fn from_str(text: &str) -> Result<Self, ParseError> {
        let bad = || ParseError(text.to_owned());
        let rest = text.strip_prefix('v').unwrap_or(text);
        // Build metadata (after a plus) takes no part in which version is later.
        let rest = rest.split_once('+').map_or(rest, |(before, _)| before);
        let (core, pre) = match rest.split_once('-') {
            Some((core, pre)) => (core, Some(pre)),
            None => (rest, None),
        };

        let mut numbers = core.split('.');
        let mut next = || -> Result<u64, ParseError> {
            let part = numbers.next().ok_or_else(bad)?;
            if part.is_empty() || !part.bytes().all(|b| b.is_ascii_digit()) {
                return Err(bad());
            }
            part.parse().map_err(|_| bad())
        };
        let (major, minor, patch) = (next()?, next()?, next()?);
        if numbers.next().is_some() {
            return Err(bad());
        }

        let pre = match pre {
            None => Vec::new(),
            Some(pre) => pre
                .split('.')
                .map(|part| {
                    if part.is_empty()
                        || !part.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
                    {
                        Err(bad())
                    } else if part.bytes().all(|b| b.is_ascii_digit()) {
                        part.parse().map(Part::Number).map_err(|_| bad())
                    } else {
                        Ok(Part::Text(part.to_owned()))
                    }
                })
                .collect::<Result<_, _>>()?,
        };
        Ok(Self {
            major,
            minor,
            patch,
            pre,
        })
    }
}

impl fmt::Display for Version {
    fn fmt(&self, out: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(out, "{}.{}.{}", self.major, self.minor, self.patch)?;
        for (i, part) in self.pre.iter().enumerate() {
            out.write_str(if i == 0 { "-" } else { "." })?;
            match part {
                Part::Number(n) => write!(out, "{n}")?,
                Part::Text(text) => out.write_str(text)?,
            }
        }
        Ok(())
    }
}

impl Ord for Version {
    fn cmp(&self, other: &Self) -> Ordering {
        (self.major, self.minor, self.patch)
            .cmp(&(other.major, other.minor, other.patch))
            .then_with(|| match (self.pre.is_empty(), other.pre.is_empty()) {
                (true, true) => Ordering::Equal,
                // A release comes after all of its pre-releases.
                (true, false) => Ordering::Greater,
                (false, true) => Ordering::Less,
                (false, false) => self.pre.cmp(&other.pre),
            })
    }
}

impl PartialOrd for Version {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

impl Ord for Part {
    fn cmp(&self, other: &Self) -> Ordering {
        match (self, other) {
            (Part::Number(a), Part::Number(b)) => a.cmp(b),
            (Part::Text(a), Part::Text(b)) => a.cmp(b),
            // Numbers come before words: alpha.1 is before alpha.beta.
            (Part::Number(_), Part::Text(_)) => Ordering::Less,
            (Part::Text(_), Part::Number(_)) => Ordering::Greater,
        }
    }
}

impl PartialOrd for Part {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v(text: &str) -> Version {
        text.parse().unwrap()
    }

    #[test]
    fn tags_and_plain_numbers_both_parse_and_print_back_the_same() {
        for (text, printed) in [
            ("2.0.0", "2.0.0"),
            ("v2.0.0", "2.0.0"),
            ("v2.0.0-alpha.4", "2.0.0-alpha.4"),
            ("10.20.30-rc.1", "10.20.30-rc.1"),
            ("1.0.0+build.5", "1.0.0"),
            ("1.0.0-beta+exp.sha.5114f85", "1.0.0-beta"),
        ] {
            assert_eq!(v(text).to_string(), printed, "{text}");
        }
    }

    #[test]
    fn what_is_not_a_version_is_refused() {
        for text in [
            "",
            "v",
            "2",
            "2.0",
            "2.0.x",
            "2.0.0.1",
            "-1.0.0",
            "2.0.0-",
            "2.0.0-alpha..1",
            "2.0.0-al pha",
            "latest",
            "2.0.0-alpha.",
            " 2.0.0",
            "99999999999999999999.0.0",
        ] {
            assert!(
                text.parse::<Version>().is_err(),
                "{text:?} should not parse"
            );
        }
    }

    #[test]
    fn the_order_is_the_one_in_the_semantic_versioning_rules() {
        // The example chain from semver.org, section 11.
        let chain = [
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0",
            "1.0.1",
            "1.1.0",
            "2.0.0-alpha.3",
            "2.0.0-alpha.4",
            "2.0.0",
            "2.0.1",
            "10.0.0",
        ];
        for pair in chain.windows(2) {
            assert!(v(pair[0]) < v(pair[1]), "{} < {}", pair[0], pair[1]);
            assert!(v(pair[1]) > v(pair[0]));
        }
        assert_eq!(v("1.0.0+a"), v("1.0.0+b"), "build metadata does not count");
        assert!(
            v("2.0.0-alpha.10") > v("2.0.0-alpha.9"),
            "numbers compare as numbers, not as text"
        );
    }

    #[test]
    fn a_person_on_an_alpha_hears_about_later_alphas_and_the_release() {
        let on = v("2.0.0-alpha.3");
        assert!(on.is_offered(&v("2.0.0-alpha.4")));
        assert!(on.is_offered(&v("2.0.0-beta.1")));
        assert!(on.is_offered(&v("2.0.0")));
        assert!(!on.is_offered(&v("2.0.0-alpha.3")), "not itself");
        assert!(!on.is_offered(&v("2.0.0-alpha.2")), "never backwards");
    }

    #[test]
    fn a_person_on_a_release_hears_only_about_releases() {
        let on = v("2.0.0");
        assert!(on.is_offered(&v("2.0.1")));
        assert!(on.is_offered(&v("3.0.0")));
        assert!(
            !on.is_offered(&v("2.1.0-rc.1")),
            "a stable user is not moved onto a pre-release"
        );
        assert!(
            !on.is_offered(&v("2.0.0-alpha.9")),
            "or back onto the alphas of what they run"
        );
        assert!(!on.is_offered(&v("2.0.0")));
        assert!(!on.is_offered(&v("1.9.9")));
    }

    #[test]
    fn this_build_has_a_version_that_update_checks_can_compare() {
        // Whatever the release workflow passes in as TELEPAD_RELEASE_VERSION has to be one.
        let version: Version = telepad_protocol::RELEASE_VERSION
            .parse()
            .expect("a version number");
        assert_eq!(version.to_string(), telepad_protocol::RELEASE_VERSION);
    }
}
