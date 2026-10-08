//! The page with the QR code, which the tray opens in the browser.
//!
//! A tray icon has no room for a QR code, and drawing a window of our own would need a toolkit for the
//! sake of one picture, so the picture is a web page. The page is served by this program, to this computer
//! only, and does four things: shows the code, counts down its time, says when a phone has paired, and
//! lets the person turn starting at login on or off or quit.
//!
//! Because it is a web server, it is built to be of no use to anyone else:
//!
//!  - it listens on `127.0.0.1` only, on a port the system picks;
//!  - every address starts with a random secret, so another page in the browser (or another user on this
//!    computer) cannot even ask for the code, and what it cannot ask for it cannot read;
//!  - a request whose `Host` is not this address is refused, which stops a web page from reaching it through
//!    a name it controls ("DNS rebinding");
//!  - a request that changes something (a POST) must come from this page, by its `Origin`;
//!  - the page loads nothing from anywhere else and a strict content policy says so.

use crate::setup;
use qrcode::render::svg;
use qrcode::{EcLevel, QrCode};
use std::io;
use std::sync::Arc;
use telepad_crypto::{random_token, secrets_equal};
use telepad_platform::access::Block;
use telepad_server::Invite;
use telepad_update::updater::State as UpdateState;

const PAGE_CSS: &str = include_str!("panel/page.css");
const PAGE_JS: &str = include_str!("panel/page.js");

/// What the page needs from the running program.
pub trait Control: Send + Sync + 'static {
    /// A new invitation for the QR code, replacing the last one. Waits for the server, so call it off the interface's thread.
    fn new_invite(&self) -> Option<Invite>;
    fn status(&self) -> PanelStatus;
    fn autostart(&self) -> bool;
    fn set_autostart(&self, on: bool) -> Result<(), String>;
    /// Whether the system is letting Telepad type and click, and how asking for it is going.
    fn setup(&self) -> SetupView;
    /// Starts asking the system for permission to type and click. It does not wait: the page watches [`Control::setup`].
    fn allow(&self);
    /// What is known about updates.
    fn update(&self) -> UpdateView;
    /// Looks for a newer Telepad. It does not wait: the page watches [`Control::update`].
    fn check_for_updates(&self);
    /// Installs the update that was found. It does not wait either.
    fn install_update(&self);
    /// Whether Telepad looks for updates by itself.
    fn set_auto_update_check(&self, on: bool) -> Result<(), String>;
    fn quit(&self);
}

/// What the page says about updates.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UpdateView {
    /// The version that is running.
    pub current: String,
    pub state: UpdateState,
    /// Whether Telepad looks by itself.
    pub auto_check: bool,
}

/// What the page says about permission to type and click.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SetupView {
    /// What is missing, or `None` when nothing is.
    pub block: Option<Block>,
    /// Asking right now: a password prompt may be open.
    pub working: bool,
    /// How the last attempt went.
    pub note: Option<String>,
    /// What the person can run in a terminal instead.
    pub terminal: Option<String>,
}

/// What the page says about the server, and what it watches for.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PanelStatus {
    pub paired: usize,
    pub connected: usize,
}

/// Who this PC is, and where this copy of Telepad writes its log, as the page names them.
#[derive(Debug, Clone)]
pub struct Identity {
    pub name: String,
    pub fingerprint: String,
    /// The log file, if there is one: what to attach to a report of a problem.
    pub log_file: Option<String>,
}

/// The running page. It stops when dropped.
pub struct Panel {
    url: String,
    server: Arc<tiny_http::Server>,
}

impl Panel {
    /// Starts serving. The address to open is [`Panel::url`].
    pub fn start(control: Arc<dyn Control>, identity: Identity) -> io::Result<Self> {
        let server = tiny_http::Server::http("127.0.0.1:0")
            .map_err(|error| io::Error::other(error.to_string()))?;
        let port = server
            .server_addr()
            .to_ip()
            .map(|address| address.port())
            .ok_or_else(|| io::Error::other("the page's socket has no address"))?;
        let server = Arc::new(server);

        let context = Context {
            control,
            identity,
            port,
            secret: make_secret(),
        };
        let url = format!("http://127.0.0.1:{port}/{}/", context.secret);
        let worker = Arc::clone(&server);
        std::thread::Builder::new()
            .name("telepad-panel".into())
            .spawn(move || serve(&worker, &context))?;
        Ok(Self { url, server })
    }

    /// The address that opens the page. It holds the secret, so it is for the person's own browser and nobody else.
    pub fn url(&self) -> &str {
        &self.url
    }
}

impl Drop for Panel {
    fn drop(&mut self) {
        self.server.unblock();
    }
}

/// A random string for the start of every address: 128 bits.
fn make_secret() -> String {
    use base64::engine::general_purpose::URL_SAFE_NO_PAD;
    use base64::Engine;
    URL_SAFE_NO_PAD.encode(random_token())
}

struct Context {
    control: Arc<dyn Control>,
    identity: Identity,
    port: u16,
    secret: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Method {
    Get,
    Post,
    Other,
}

/// What a request says that matters here.
struct Request {
    method: Method,
    url: String,
    host: Option<String>,
    origin: Option<String>,
}

struct Reply {
    status: u16,
    content_type: &'static str,
    body: Vec<u8>,
    location: Option<String>,
}

impl Reply {
    fn html(body: String) -> Self {
        Self {
            status: 200,
            content_type: "text/html; charset=utf-8",
            body: body.into_bytes(),
            location: None,
        }
    }

    fn text(status: u16, body: &str) -> Self {
        Self {
            status,
            content_type: "text/plain; charset=utf-8",
            body: body.as_bytes().to_vec(),
            location: None,
        }
    }

    fn redirect(status: u16, to: &str) -> Self {
        Self {
            status,
            content_type: "text/plain; charset=utf-8",
            body: Vec::new(),
            location: Some(to.to_owned()),
        }
    }
}

fn serve(server: &tiny_http::Server, context: &Context) {
    for request in server.incoming_requests() {
        let method = match request.method() {
            tiny_http::Method::Get => Method::Get,
            tiny_http::Method::Post => Method::Post,
            _ => Method::Other,
        };
        let header = |name: &'static str| {
            request
                .headers()
                .iter()
                .find(|h| h.field.as_str().as_str().eq_ignore_ascii_case(name))
                .map(|h| h.value.as_str().to_owned())
        };
        let parsed = Request {
            method,
            url: request.url().to_owned(),
            host: header("Host"),
            origin: header("Origin"),
        };
        let reply = respond(context, &parsed);

        let mut response =
            tiny_http::Response::from_data(reply.body).with_status_code(reply.status);
        let mut add = |name: &'static str, value: &str| {
            if let Ok(header) = tiny_http::Header::from_bytes(name.as_bytes(), value.as_bytes()) {
                response.add_header(header);
            }
        };
        add("Content-Type", reply.content_type);
        add("Cache-Control", "no-store");
        add("X-Content-Type-Options", "nosniff");
        add("Referrer-Policy", "no-referrer");
        add("Cross-Origin-Resource-Policy", "same-origin");
        add(
            "Content-Security-Policy",
            "default-src 'none'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; \
             form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
        );
        if let Some(location) = &reply.location {
            add("Location", location);
        }
        let _ = request.respond(response);
    }
}

/// Decides what to answer. It touches nothing but `context`, so it is tested without a socket.
fn respond(context: &Context, request: &Request) -> Reply {
    if !context.allows_host(request.host.as_deref()) {
        return Reply::text(400, "This page is for the computer it runs on.");
    }

    let (path, query) = request
        .url
        .split_once('?')
        .unwrap_or((request.url.as_str(), ""));
    let mut parts = path.trim_start_matches('/').splitn(2, '/');
    let first = parts.next().unwrap_or("");
    if !secrets_equal(first.as_bytes(), context.secret.as_bytes()) {
        return Reply::text(404, "Not found.");
    }
    let Some(rest) = parts.next() else {
        // The page's own files are found relative to it, which needs the slash.
        return Reply::redirect(308, &format!("/{}/", context.secret));
    };

    // Changing something has to come from this page.
    if request.method == Method::Post && !context.allows_origin(request.origin.as_deref()) {
        return Reply::text(403, "This request did not come from the Telepad page.");
    }

    match (request.method, rest) {
        (Method::Get, "") => context.page(),
        (Method::Get, "status.json") => context.status_json(),
        (Method::Get, "app.css") => Reply {
            status: 200,
            content_type: "text/css; charset=utf-8",
            body: PAGE_CSS.as_bytes().to_vec(),
            location: None,
        },
        (Method::Get, "app.js") => Reply {
            status: 200,
            content_type: "text/javascript; charset=utf-8",
            body: PAGE_JS.as_bytes().to_vec(),
            location: None,
        },
        (Method::Post, "new") => Reply::redirect(303, "./"),
        (Method::Post, "allow") => {
            context.control.allow();
            Reply::text(204, "")
        }
        (Method::Post, "check-updates") => {
            context.control.check_for_updates();
            Reply::text(204, "")
        }
        (Method::Post, "install-update") => {
            context.control.install_update();
            Reply::text(204, "")
        }
        (Method::Post, "auto-updates") => {
            let on = query.split('&').any(|pair| pair == "on=1");
            match context.control.set_auto_update_check(on) {
                Ok(()) => Reply::text(204, ""),
                Err(message) => Reply::text(500, &message),
            }
        }
        (Method::Post, "autostart") => {
            let on = query.split('&').any(|pair| pair == "on=1");
            match context.control.set_autostart(on) {
                Ok(()) => Reply::redirect(303, "./"),
                Err(message) => context.message_page("Telepad could not change that.", &message),
            }
        }
        (Method::Post, "quit") => {
            context.control.quit();
            context.message_page(
                "Telepad has quit.",
                "You can close this page. Start Telepad again when you need it.",
            )
        }
        (Method::Other, _) => Reply::text(405, "Not allowed."),
        _ => Reply::text(404, "Not found."),
    }
}

impl Context {
    fn allows_host(&self, host: Option<&str>) -> bool {
        matches!(host, Some(h) if h == format!("127.0.0.1:{}", self.port) || h == format!("localhost:{}", self.port))
    }

    fn allows_origin(&self, origin: Option<&str>) -> bool {
        match origin {
            // A form posted from this page carries its origin; one posted by a program that sends none is not a web page.
            None => true,
            Some(o) => {
                o == format!("http://127.0.0.1:{}", self.port)
                    || o == format!("http://localhost:{}", self.port)
            }
        }
    }

    fn status_json(&self) -> Reply {
        let status = self.control.status();
        let setup = self.control.setup();
        let text = |value: &Option<String>| value.as_deref().map_or("null".to_owned(), json_string);
        Reply {
            status: 200,
            content_type: "application/json",
            body: format!(
                "{{\"paired\":{},\"connected\":{},\"blocked\":{},\"working\":{},\"note\":{},\"terminal\":{},\"update\":{}}}",
                status.paired,
                status.connected,
                setup.block.is_some(),
                setup.working,
                text(&setup.note),
                text(&setup.terminal),
                update_json(&self.control.update()),
            )
            .into_bytes(),
            location: None,
        }
    }

    /// The page with a fresh code on it.
    fn page(&self) -> Reply {
        let Some(invite) = self.control.new_invite() else {
            return self.message_page("Telepad could not make a code.", "Try again in a moment.");
        };
        let Some(qr) = qr_svg(&invite.url) else {
            return self.message_page(
                "Telepad could not draw the code.",
                "This PC's name may be too long.",
            );
        };
        let status = self.control.status();
        let autostart = self.control.autostart();
        Reply::html(render_page(&PageData {
            qr_svg: &qr,
            addresses: invite
                .addresses
                .iter()
                .map(ToString::to_string)
                .collect::<Vec<_>>()
                .join(" or "),
            version: telepad_protocol::RELEASE_VERSION,
            log_file: self.identity.log_file.as_deref(),
            name: &self.identity.name,
            fingerprint: &self.identity.fingerprint,
            seconds: invite.expires_in.as_secs(),
            paired: status.paired,
            connected: status.connected,
            autostart,
            setup: self.control.setup(),
        }))
    }

    fn message_page(&self, title: &str, body: &str) -> Reply {
        Reply::html(format!(
            "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\
             <title>Telepad</title><link rel=\"stylesheet\" href=\"app.css\"></head><body><main class=\"gone\"><h1>{}</h1><p>{}</p></main></body></html>",
            escape(title),
            escape(body)
        ))
    }
}

/// The QR code as an inline SVG, or `None` if the text is too long for one.
fn qr_svg(text: &str) -> Option<String> {
    // Medium error correction: this is read off a screen in whatever light the room has.
    let code = QrCode::with_error_correction_level(text, EcLevel::M).ok()?;
    let drawing = code
        .render::<svg::Color>()
        .min_dimensions(300, 300)
        .dark_color(svg::Color("#0b1220"))
        .light_color(svg::Color("#ffffff"))
        .quiet_zone(true)
        .build();
    // The XML declaration belongs to a file; inside a page it is an error.
    Some(match drawing.split_once("?>") {
        Some((_, svg)) => svg.trim_start().to_owned(),
        None => drawing,
    })
}

struct PageData<'a> {
    qr_svg: &'a str,
    /// The PC's addresses, to type into the app when the code cannot be scanned ("" when there are none).
    addresses: String,
    version: &'a str,
    log_file: Option<&'a str>,
    name: &'a str,
    fingerprint: &'a str,
    seconds: u64,
    paired: usize,
    connected: usize,
    autostart: bool,
    setup: SetupView,
}

/// A JSON string: quoted, with what a string may not hold written out.
fn json_string(text: &str) -> String {
    let mut out = String::with_capacity(text.len() + 2);
    out.push('"');
    for c in text.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

/// What the page needs to know about updates, as JSON. Every string in it is shown with `textContent`, never as
/// markup, and the one address (`page`) is checked again by the script before it is used.
fn update_json(view: &UpdateView) -> String {
    let text = |value: Option<&str>| value.map_or("null".to_owned(), json_string);
    let mut phase = "unknown";
    let mut message = None;
    let mut how = None;
    let mut declined = false;
    let mut percent = None;
    match &view.state {
        UpdateState::Unknown => {}
        UpdateState::Checking => phase = "checking",
        UpdateState::UpToDate => phase = "up_to_date",
        UpdateState::Available(_) => phase = "available",
        UpdateState::Downloading { done, total, .. } => {
            phase = "downloading";
            percent = total
                .filter(|total| *total > 0)
                .map(|total| (done.min(&total) * 100 / total) as u8);
        }
        UpdateState::Installing(_) => phase = "installing",
        UpdateState::Restarting(_) => phase = "restarting",
        UpdateState::CheckFailed(why) => {
            phase = "check_failed";
            message = Some(why.as_str());
        }
        UpdateState::InstallFailed {
            message: why,
            how: steps,
            declined: closed,
            ..
        } => {
            phase = "install_failed";
            message = Some(why.as_str());
            how = steps.as_deref();
            declined = *closed;
        }
    }
    let found = view.state.update();
    format!(
        "{{\"phase\":{},\"current\":{},\"version\":{},\"page\":{},\"can_install\":{},\"by_hand\":{},\"percent\":{},\"message\":{},\"how\":{},\"declined\":{},\"auto\":{}}}",
        json_string(phase),
        json_string(&view.current),
        text(found.map(|u| u.release.version.to_string()).as_deref()),
        text(found.map(|u| u.release.page.as_str())),
        found.is_some_and(|u| u.install.is_some()),
        text(found.and_then(|u| u.by_hand.as_deref())),
        percent.map_or("null".to_owned(), |p| p.to_string()),
        text(message),
        text(how),
        declined,
        view.auto_check,
    )
}

/// The notice at the top of the page while the system is not letting Telepad type and click ("" otherwise).
fn allow_notice(setup: &SetupView) -> String {
    let Some(block) = setup.block else {
        return String::new();
    };
    let words = setup::words(block);
    let hidden = |shown: bool| if shown { "" } else { " hidden" };
    format!(
        "<section id=\"allow\" class=\"notice\" role=\"alert\">\n\
<h2>{title}</h2>\n\
<p>{body}</p>\n\
<button type=\"button\" id=\"allow-button\"{disabled}>{button}</button>\n\
<p id=\"allow-note\" class=\"hint\"{note_hidden}>{note}</p>\n\
<pre id=\"allow-terminal\"{terminal_hidden}>{terminal}</pre>\n\
</section>\n",
        title = escape(words.title),
        body = escape(words.body),
        button = escape(words.button),
        disabled = if setup.working { " disabled" } else { "" },
        note_hidden = hidden(setup.note.is_some()),
        note = escape(setup.note.as_deref().unwrap_or("")),
        terminal_hidden = hidden(setup.terminal.is_some()),
        terminal = escape(setup.terminal.as_deref().unwrap_or("")),
    )
}

fn render_page(data: &PageData<'_>) -> String {
    let clock = {
        let (m, s) = (data.seconds / 60, data.seconds % 60);
        format!("{m}:{s:02}")
    };
    let status = if data.connected > 0 {
        phones(data.connected, "connected")
    } else if data.paired > 0 {
        phones(data.paired, "paired")
    } else {
        "No phone is paired yet.".to_owned()
    };
    // For when the code cannot be scanned (no camera, a dark room): the address to type into the app.
    let address_line = if data.addresses.is_empty() {
        String::new()
    } else {
        format!(
            "<p class=\"hint\">Cannot scan? In the app choose <b>Add device</b>, then <b>Address</b>, and enter <code>{}</code>.</p>\n",
            escape(&data.addresses)
        )
    };
    let (toggle_value, toggle_label) = if data.autostart {
        ("0", "Starts at login: on")
    } else {
        ("1", "Starts at login: off")
    };

    format!(
        "<!doctype html>\n\
<html lang=\"en\">\n\
<head>\n\
<meta charset=\"utf-8\">\n\
<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n\
<title>Telepad: pair a phone</title>\n\
<link rel=\"stylesheet\" href=\"app.css\">\n\
</head>\n\
<body>\n\
<main>\n\
<header>\n\
<svg viewBox=\"0 0 108 108\" aria-hidden=\"true\"><rect width=\"108\" height=\"108\" rx=\"24\" fill=\"#0f1a2b\"/>\
<g transform=\"translate(54 54) scale(1.3) translate(-54 -54)\"><path d=\"M35,35H73A7,7 0 0 1 80,42V66A7,7 0 0 1 73,73H35A7,7 0 0 1 28,66V42A7,7 0 0 1 35,35Z\" fill=\"#fff\" fill-opacity=\".14\" stroke=\"#fff\" stroke-opacity=\".95\" stroke-width=\"3.2\"/>\
<path d=\"M29.6,62H78.4M54,62V71.4\" stroke=\"#fff\" stroke-opacity=\".5\" stroke-width=\"2.2\" fill=\"none\"/>\
<path d=\"M44,41L44,58L48.5,53.8L51.8,61L55,59.6L51.7,52.6L58,52.2Z\" fill=\"#38bdf8\"/></g></svg>\n\
<h1>Pair a phone</h1>\n\
</header>\n\
{allow_notice}\
<p id=\"allowed\" class=\"done\" hidden>&#10003; Allowed. Telepad can type and click now.</p>\n\
<section id=\"update\" class=\"notice\" hidden>\n\
<h2 id=\"update-title\"></h2>\n\
<p id=\"update-text\"></p>\n\
<progress id=\"update-progress\" max=\"100\" hidden></progress>\n\
<button type=\"button\" id=\"update-button\" hidden>Install and restart</button>\n\
<a id=\"update-link\" target=\"_blank\" rel=\"noreferrer noopener\" hidden>What changed</a>\n\
<pre id=\"update-how\" hidden></pre>\n\
</section>\n\
<section id=\"code\" class=\"card\">\n\
<div class=\"qr\" role=\"img\" aria-label=\"QR code for pairing a phone with {name}\">{qr}</div>\n\
<p class=\"timer\">Good for <span id=\"countdown\">{clock}</span>, and for one phone.</p>\n\
</section>\n\
<ol id=\"steps\" class=\"steps\">\n\
<li>Open Telepad on your phone, on the same Wi-Fi as this computer.</li>\n\
<li>Tap <b>Scan QR code</b> and point the camera at the code above.</li>\n\
</ol>\n\
{address_line}\
<p id=\"done\" class=\"done\" hidden>&#10003; Paired. You can close this page.</p>\n\
<div id=\"expired\" class=\"expired\" hidden>\n\
<p>This code has run out.</p>\n\
<form method=\"post\" action=\"new\"><button type=\"submit\">Show a new code</button></form>\n\
</div>\n\
<details>\n\
<summary>This PC</summary>\n\
<p>Name: <code>{name}</code><br>Fingerprint: <code>{fingerprint}</code></p>\n\
<p>The code carries this PC's key, so there is nothing to compare. If you pair another way, the phone shows this fingerprint to check.</p>\n\
<p>Telepad {version}{log_line}</p>\n\
</details>\n\
<footer>\n\
<p id=\"status\" class=\"status\">{status}</p>\n\
<form method=\"post\" action=\"autostart?on={toggle_value}\"><button type=\"submit\" class=\"quiet\">{toggle_label}</button></form>\n\
<form method=\"post\" action=\"quit\"><button type=\"submit\" class=\"quiet\">Quit Telepad</button></form>\n\
<p id=\"update-line\" class=\"status\">Telepad {version}</p>\n\
<button type=\"button\" id=\"check-updates\" class=\"quiet\">Check for updates</button>\n\
<button type=\"button\" id=\"auto-updates\" class=\"quiet\">Looks for updates: on</button>\n\
</footer>\n\
<p class=\"links\"><a href=\"https://github.com/omsingh02/telepad/releases\" target=\"_blank\" rel=\"noreferrer noopener\">Releases</a> &middot; \
<a href=\"https://github.com/omsingh02/telepad/issues/new/choose\" target=\"_blank\" rel=\"noreferrer noopener\">Report a problem</a> &middot; \
<a href=\"https://github.com/omsingh02/telepad/blob/main/PRIVACY.md\" target=\"_blank\" rel=\"noreferrer noopener\">Privacy</a> &middot; \
<a href=\"https://github.com/omsingh02/telepad/blob/main/THIRD_PARTY_LICENSES.md\" target=\"_blank\" rel=\"noreferrer noopener\">Licenses</a></p>\n\
</main>\n\
<div id=\"state\" data-paired=\"{paired}\" data-seconds=\"{seconds}\" data-blocked=\"{blocked}\" hidden></div>\n\
<script src=\"app.js\"></script>\n\
</body>\n\
</html>\n",
        qr = data.qr_svg,
        allow_notice = allow_notice(&data.setup),
        address_line = address_line,
        version = escape(data.version),
        log_line = data.log_file.map_or_else(String::new, |path| {
            format!(", log file: <code>{}</code>", escape(path))
        }),
        name = escape(data.name),
        fingerprint = escape(data.fingerprint),
        paired = data.paired,
        seconds = data.seconds,
        blocked = u8::from(data.setup.block.is_some()),
    )
}

fn phones(count: usize, what: &str) -> String {
    if count == 1 {
        format!("1 phone is {what}.")
    } else {
        format!("{count} phones are {what}.")
    }
}

/// Text for the inside of an element or a quoted attribute.
fn escape(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for c in text.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&#39;"),
            other => out.push(other),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpStream;
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
    use std::sync::Mutex;
    use std::time::Duration;

    /// Waits this long for the page's server to answer.
    const TEST_PATIENCE: Duration = Duration::from_secs(3);

    struct Fake {
        invites: AtomicUsize,
        autostart: AtomicBool,
        quit: AtomicBool,
        paired: AtomicUsize,
        failing_autostart: AtomicBool,
        setup: Mutex<SetupView>,
        allows: AtomicUsize,
        update: Mutex<UpdateView>,
        checks: AtomicUsize,
        installs: AtomicUsize,
        names: Mutex<Vec<String>>,
        addresses: Mutex<Vec<std::net::Ipv4Addr>>,
    }

    impl Fake {
        fn new() -> Arc<Self> {
            Arc::new(Self {
                invites: AtomicUsize::new(0),
                autostart: AtomicBool::new(false),
                quit: AtomicBool::new(false),
                paired: AtomicUsize::new(0),
                failing_autostart: AtomicBool::new(false),
                setup: Mutex::new(SetupView::default()),
                allows: AtomicUsize::new(0),
                update: Mutex::new(UpdateView {
                    current: "2.0.0-alpha.3".into(),
                    state: UpdateState::Unknown,
                    auto_check: true,
                }),
                checks: AtomicUsize::new(0),
                installs: AtomicUsize::new(0),
                names: Mutex::new(Vec::new()),
                addresses: Mutex::new(vec![
                    std::net::Ipv4Addr::new(192, 168, 1, 20),
                    std::net::Ipv4Addr::new(10, 0, 0, 5),
                ]),
            })
        }
    }

    impl Control for Fake {
        fn new_invite(&self) -> Option<Invite> {
            let n = self.invites.fetch_add(1, Ordering::SeqCst) + 1;
            self.names.lock().unwrap().push(format!("invite {n}"));
            Some(Invite {
                url: format!("telepad://pair?v=1&k=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&t=AAAAAAAAAAAAAAAAAAAAAA&p=5000&n=test{n}"),
                addresses: self.addresses.lock().unwrap().clone(),
                expires_in: Duration::from_secs(300),
            })
        }
        fn status(&self) -> PanelStatus {
            PanelStatus {
                paired: self.paired.load(Ordering::SeqCst),
                connected: 0,
            }
        }
        fn autostart(&self) -> bool {
            self.autostart.load(Ordering::SeqCst)
        }
        fn set_autostart(&self, on: bool) -> Result<(), String> {
            if self.failing_autostart.load(Ordering::SeqCst) {
                return Err("the disk is full".into());
            }
            self.autostart.store(on, Ordering::SeqCst);
            Ok(())
        }
        fn setup(&self) -> SetupView {
            self.setup.lock().unwrap().clone()
        }
        fn allow(&self) {
            self.allows.fetch_add(1, Ordering::SeqCst);
        }
        fn update(&self) -> UpdateView {
            self.update.lock().unwrap().clone()
        }
        fn check_for_updates(&self) {
            self.checks.fetch_add(1, Ordering::SeqCst);
        }
        fn install_update(&self) {
            self.installs.fetch_add(1, Ordering::SeqCst);
        }
        fn set_auto_update_check(&self, on: bool) -> Result<(), String> {
            if self.failing_autostart.load(Ordering::SeqCst) {
                return Err("the disk is full".into());
            }
            self.update.lock().unwrap().auto_check = on;
            Ok(())
        }
        fn quit(&self) {
            self.quit.store(true, Ordering::SeqCst);
        }
    }

    fn context(fake: &Arc<Fake>) -> Context {
        Context {
            control: fake.clone(),
            identity: Identity {
                name: "DESKTOP-PC".into(),
                fingerprint: "7F2A · B9C1 · 4E08 · 91D3 · 0AC7".into(),
                log_file: Some("/home/me/.config/telepad/telepad.log".into()),
            },
            port: 4321,
            secret: "SECRET".into(),
        }
    }

    fn get(path: &str) -> Request {
        Request {
            method: Method::Get,
            url: path.into(),
            host: Some("127.0.0.1:4321".into()),
            origin: None,
        }
    }

    fn post(path: &str) -> Request {
        Request {
            method: Method::Post,
            url: path.into(),
            host: Some("127.0.0.1:4321".into()),
            origin: Some("http://127.0.0.1:4321".into()),
        }
    }

    fn body(reply: &Reply) -> String {
        String::from_utf8(reply.body.clone()).unwrap()
    }

    // ── What is shown ────────────────────────────────────────────────

    #[test]
    fn the_page_shows_a_fresh_code_the_name_and_the_time_it_lasts() {
        let fake = Fake::new();
        let reply = respond(&context(&fake), &get("/SECRET/"));
        let page = body(&reply);

        assert_eq!(reply.status, 200);
        assert!(page.contains("<svg"), "the QR code is drawn into the page");
        assert!(
            !page.contains("<?xml"),
            "an XML declaration inside a page is an error"
        );
        assert!(page.contains("5:00"), "five minutes");
        assert!(page.contains("DESKTOP-PC"));
        assert!(page.contains("7F2A · B9C1 · 4E08 · 91D3 · 0AC7"));
        assert!(page.contains("No phone is paired yet."));
        assert_eq!(fake.invites.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn the_page_says_what_to_type_when_the_code_cannot_be_scanned() {
        let fake = Fake::new();
        let page = body(&respond(&context(&fake), &get("/SECRET/")));
        assert!(
            page.contains("Add device</b>, then <b>Address</b>, and enter <code>192.168.1.20 or 10.0.0.5</code>"),
            "{page}"
        );

        // A PC that knows no address has nothing to offer, and says nothing rather than something wrong.
        fake.addresses.lock().unwrap().clear();
        let page = body(&respond(&context(&fake), &get("/SECRET/")));
        assert!(!page.contains("Cannot scan?"), "{page}");
    }

    #[test]
    fn the_page_says_which_version_this_is_where_the_log_is_and_where_to_get_help() {
        let fake = Fake::new();
        let page = body(&respond(&context(&fake), &get("/SECRET/")));
        assert!(page.contains(&format!("Telepad {}", telepad_protocol::RELEASE_VERSION)));
        assert!(page.contains("log file: <code>/home/me/.config/telepad/telepad.log</code>"));
        for link in [
            "/releases\"",
            "/issues/new/choose\"",
            "/PRIVACY.md\"",
            "/THIRD_PARTY_LICENSES.md\"",
        ] {
            assert!(page.contains(link), "a link ending {link}");
        }
        // A link that opens another site must not hand it this page's secret address.
        assert_eq!(
            page.matches("target=\"_blank\"").count(),
            page.matches("rel=\"noreferrer noopener\"").count()
        );

        // Without a log file there is nothing to point at.
        let mut context = context(&fake);
        context.identity.log_file = None;
        assert!(!body(&respond(&context, &get("/SECRET/"))).contains("log file"));
    }

    #[test]
    fn every_visit_makes_a_new_code_and_the_status_does_not() {
        let fake = Fake::new();
        let ctx = context(&fake);
        respond(&ctx, &get("/SECRET/"));
        respond(&ctx, &get("/SECRET/"));
        assert_eq!(fake.invites.load(Ordering::SeqCst), 2);

        for _ in 0..5 {
            respond(&ctx, &get("/SECRET/status.json"));
        }
        assert_eq!(
            fake.invites.load(Ordering::SeqCst),
            2,
            "watching must not replace the code on the screen"
        );
    }

    #[test]
    fn the_status_says_how_many_are_paired() {
        let fake = Fake::new();
        fake.paired.store(2, Ordering::SeqCst);
        let reply = respond(&context(&fake), &get("/SECRET/status.json"));
        assert_eq!(reply.content_type, "application/json");
        assert!(
            body(&reply).starts_with(
                "{\"paired\":2,\"connected\":0,\"blocked\":false,\"working\":false,\"note\":null,\"terminal\":null,\"update\":{"
            ),
            "{}",
            body(&reply)
        );
    }

    #[test]
    fn a_name_cannot_inject_into_the_page() {
        let fake = Fake::new();
        let mut ctx = context(&fake);
        ctx.identity.name = "<script>alert(1)</script>\" onload=\"x".into();
        let page = body(&respond(&ctx, &get("/SECRET/")));
        assert!(!page.contains("<script>alert"));
        assert!(page.contains("&lt;script&gt;alert(1)&lt;/script&gt;&quot; onload=&quot;x"));
    }

    #[test]
    fn the_page_runs_no_script_of_its_own_so_the_policy_can_forbid_it() {
        let fake = Fake::new();
        let page = body(&respond(&context(&fake), &get("/SECRET/")));
        for forbidden in ["onclick=", "onload=", "javascript:", "<style"] {
            assert!(!page.contains(forbidden), "{forbidden}");
        }
        assert_eq!(
            page.matches("<script").count(),
            1,
            "one script, from the page's own address"
        );
        assert!(page.contains("<script src=\"app.js\">"));
    }

    #[test]
    fn the_script_and_the_style_are_served() {
        let fake = Fake::new();
        let ctx = context(&fake);
        assert!(body(&respond(&ctx, &get("/SECRET/app.js"))).contains("status.json"));
        assert!(body(&respond(&ctx, &get("/SECRET/app.css"))).contains("--accent"));
    }

    // ── Permission to type and click ─────────────────────────────────

    fn blocked(block: Block) -> SetupView {
        SetupView {
            block: Some(block),
            ..Default::default()
        }
    }

    #[test]
    fn when_nothing_is_missing_the_page_has_no_notice() {
        let fake = Fake::new();
        let page = body(&respond(&context(&fake), &get("/SECRET/")));
        assert!(!page.contains("id=\"allow\""), "{page}");
        assert!(!page.contains("allow-button"));
        assert!(page.contains("data-blocked=\"0\""));
    }

    #[test]
    fn when_the_system_is_not_letting_telepad_type_the_page_says_so_first_and_has_a_button() {
        for (block, title, button) in [
            (
                Block::LinuxUinput,
                "Allow Telepad to type and click",
                "Allow…",
            ),
            (
                Block::MacAccessibility,
                "Allow Telepad to control this Mac",
                "Open Accessibility settings",
            ),
        ] {
            let fake = Fake::new();
            *fake.setup.lock().unwrap() = blocked(block);
            let page = body(&respond(&context(&fake), &get("/SECRET/")));
            assert!(page.contains(title), "{page}");
            assert!(page.contains(&format!(">{button}</button>")), "{page}");
            assert!(page.contains("data-blocked=\"1\""));
            assert!(
                page.find("id=\"allow\"") < page.find("id=\"code\""),
                "the notice is above the code"
            );
        }
    }

    #[test]
    fn what_happened_and_what_to_type_by_hand_are_shown_and_cannot_inject() {
        let fake = Fake::new();
        *fake.setup.lock().unwrap() = SetupView {
            block: Some(Block::LinuxUinput),
            working: true,
            note: Some("That did not work: <b>no</b>".into()),
            terminal: Some("sudo sh -c 'echo \"hi\" > /x'".into()),
        };
        let page = body(&respond(&context(&fake), &get("/SECRET/")));
        assert!(
            page.contains("That did not work: &lt;b&gt;no&lt;/b&gt;"),
            "{page}"
        );
        assert!(
            page.contains("sudo sh -c &#39;echo &quot;hi&quot; &gt; /x&#39;"),
            "{page}"
        );
        assert!(!page.contains("<b>no</b>"));
        assert!(
            page.contains("id=\"allow-button\" disabled"),
            "asking: the button waits"
        );
    }

    #[test]
    fn the_status_carries_what_the_page_needs_to_follow_the_asking() {
        let fake = Fake::new();
        *fake.setup.lock().unwrap() = SetupView {
            block: Some(Block::LinuxUinput),
            working: true,
            note: Some("line one\n\"two\"".into()),
            terminal: None,
        };
        let json = body(&respond(&context(&fake), &get("/SECRET/status.json")));
        assert!(
            json.starts_with(
                "{\"paired\":0,\"connected\":0,\"blocked\":true,\"working\":true,\"note\":\"line one\\n\\\"two\\\"\",\"terminal\":null,\"update\":{"
            ),
            "{json}"
        );
    }

    #[test]
    fn json_strings_are_escaped_whatever_they_hold() {
        assert_eq!(json_string("plain"), "\"plain\"");
        assert_eq!(json_string("a\"b\\c"), "\"a\\\"b\\\\c\"");
        assert_eq!(json_string("1\n2\r3\t4"), "\"1\\n2\\r3\\t4\"");
        assert_eq!(json_string("\u{1}"), "\"\\u0001\"");
        assert_eq!(json_string("é✓"), "\"é✓\"");
    }

    #[test]
    fn the_allow_button_asks_once_and_only_when_it_comes_from_the_page() {
        let fake = Fake::new();
        let ctx = context(&fake);
        let reply = respond(&ctx, &post("/SECRET/allow"));
        assert_eq!(reply.status, 204);
        assert_eq!(fake.allows.load(Ordering::SeqCst), 1);

        let mut elsewhere = post("/SECRET/allow");
        elsewhere.origin = Some("http://evil.example".into());
        assert_eq!(respond(&ctx, &elsewhere).status, 403);
        assert_eq!(
            respond(&ctx, &get("/SECRET/allow")).status,
            404,
            "asking is not a link to follow"
        );
        assert_eq!(fake.allows.load(Ordering::SeqCst), 1);
    }

    // ── Updates ──────────────────────────────────────────────────────

    fn found(version: &str, installable: bool) -> telepad_update::fetch::Available {
        use telepad_update::release::{Asset, Release, Source};
        let release = Release::for_tag(&format!("v{version}"), &Source::github()).unwrap();
        let asset = |name: &str| Asset {
            name: name.into(),
            size: 1,
            url: String::new(),
        };
        telepad_update::fetch::Available {
            release,
            install: installable.then(|| telepad_update::fetch::Install {
                asset: asset("telepad.deb"),
                sums: asset("SHA256SUMS"),
            }),
            by_hand: (!installable).then(|| "Homebrew installed Telepad.".to_owned()),
        }
    }

    fn update_json_of(fake: &Arc<Fake>) -> String {
        let json = body(&respond(&context(fake), &get("/SECRET/status.json")));
        json[json.find("\"update\":").unwrap() + 9..json.len() - 1].to_owned()
    }

    #[test]
    fn nothing_known_about_updates_says_only_which_version_this_is() {
        let fake = Fake::new();
        assert_eq!(
            update_json_of(&fake),
            "{\"phase\":\"unknown\",\"current\":\"2.0.0-alpha.3\",\"version\":null,\"page\":null,\"can_install\":false,\"by_hand\":null,\"percent\":null,\"message\":null,\"how\":null,\"declined\":false,\"auto\":true}"
        );
    }

    #[test]
    fn an_update_that_can_be_installed_says_so_and_names_the_page() {
        let fake = Fake::new();
        fake.update.lock().unwrap().state = UpdateState::Available(found("2.0.0-alpha.4", true));
        let json = update_json_of(&fake);
        assert!(json.contains("\"phase\":\"available\""), "{json}");
        assert!(json.contains("\"version\":\"2.0.0-alpha.4\""));
        assert!(json.contains(
            "\"page\":\"https://github.com/omsingh02/telepad/releases/tag/v2.0.0-alpha.4\""
        ));
        assert!(json.contains("\"can_install\":true"));
        assert!(json.contains("\"by_hand\":null"));
    }

    #[test]
    fn an_update_that_cannot_be_installed_here_says_why() {
        let fake = Fake::new();
        fake.update.lock().unwrap().state = UpdateState::Available(found("2.0.0-alpha.4", false));
        let json = update_json_of(&fake);
        assert!(json.contains("\"can_install\":false"), "{json}");
        assert!(json.contains("\"by_hand\":\"Homebrew installed Telepad.\""));
    }

    #[test]
    fn a_download_reports_how_far_it_is_and_never_more_than_all() {
        let fake = Fake::new();
        let downloading = |done, total| UpdateState::Downloading {
            update: found("2.0.0-alpha.4", true),
            done,
            total,
        };
        for (done, total, expect) in [
            (0, Some(200), "0"),
            (50, Some(200), "25"),
            (200, Some(200), "100"),
            (999, Some(200), "100"),
            (5, None, "null"),
            (5, Some(0), "null"),
        ] {
            fake.update.lock().unwrap().state = downloading(done, total);
            let json = update_json_of(&fake);
            assert!(
                json.contains(&format!("\"percent\":{expect},")),
                "{done}/{total:?}: {json}"
            );
            assert!(json.contains("\"phase\":\"downloading\""));
        }
    }

    #[test]
    fn a_failure_carries_its_words_the_steps_for_by_hand_and_whether_the_person_just_said_no() {
        let fake = Fake::new();
        fake.update.lock().unwrap().state = UpdateState::InstallFailed {
            update: found("2.0.0-alpha.4", true),
            message: "It said \"no\".".into(),
            how: Some("sudo apt-get install -y /tmp/x.deb".into()),
            declined: true,
        };
        let json = update_json_of(&fake);
        assert!(json.contains("\"phase\":\"install_failed\""), "{json}");
        assert!(json.contains("\"message\":\"It said \\\"no\\\".\""));
        assert!(json.contains("\"how\":\"sudo apt-get install -y /tmp/x.deb\""));
        assert!(json.contains("\"declined\":true"));

        fake.update.lock().unwrap().state = UpdateState::CheckFailed("GitHub is down".into());
        let json = update_json_of(&fake);
        assert!(
            json.contains("\"phase\":\"check_failed\"")
                && json.contains("\"message\":\"GitHub is down\""),
            "{json}"
        );
    }

    #[test]
    fn the_page_has_the_places_the_script_fills_in_and_nothing_filled_in_by_the_server() {
        let fake = Fake::new();
        fake.update.lock().unwrap().state = UpdateState::Available(found("9.8.7-test.1", true));
        let page = body(&respond(&context(&fake), &get("/SECRET/")));
        for id in [
            "update",
            "update-title",
            "update-text",
            "update-progress",
            "update-button",
            "update-link",
            "update-how",
            "update-line",
            "check-updates",
            "auto-updates",
        ] {
            assert!(page.contains(&format!("id=\"{id}\"")), "{id}");
        }
        assert!(
            page.contains("<section id=\"update\" class=\"notice\" hidden>"),
            "hidden until the script has the state"
        );
        assert!(
            !page.contains("9.8.7-test.1"),
            "what was found reaches the page only as data, through the script (a version that no build is, since a release build puts its own version on the page)"
        );
    }

    #[test]
    fn the_update_buttons_work_and_only_from_the_page() {
        let fake = Fake::new();
        let ctx = context(&fake);
        assert_eq!(respond(&ctx, &post("/SECRET/check-updates")).status, 204);
        assert_eq!(respond(&ctx, &post("/SECRET/install-update")).status, 204);
        assert_eq!(
            respond(&ctx, &post("/SECRET/auto-updates?on=0")).status,
            204
        );
        assert_eq!(
            (
                fake.checks.load(Ordering::SeqCst),
                fake.installs.load(Ordering::SeqCst)
            ),
            (1, 1)
        );
        assert!(!fake.update.lock().unwrap().auto_check);
        assert_eq!(
            respond(&ctx, &post("/SECRET/auto-updates?on=1")).status,
            204
        );
        assert!(fake.update.lock().unwrap().auto_check);

        fake.failing_autostart.store(true, Ordering::SeqCst);
        assert_eq!(
            respond(&ctx, &post("/SECRET/auto-updates?on=0")).status,
            500
        );

        for path in [
            "/SECRET/check-updates",
            "/SECRET/install-update",
            "/SECRET/auto-updates?on=0",
        ] {
            let mut elsewhere = post(path);
            elsewhere.origin = Some("http://evil.example".into());
            assert_eq!(respond(&ctx, &elsewhere).status, 403, "{path}");
            assert_eq!(
                respond(&ctx, &get(path)).status,
                404,
                "{path} is not a link to follow"
            );
        }
        assert_eq!(
            (
                fake.checks.load(Ordering::SeqCst),
                fake.installs.load(Ordering::SeqCst)
            ),
            (1, 1),
            "nothing from elsewhere got through"
        );
    }

    // ── Who may ask ──────────────────────────────────────────────────

    #[test]
    fn without_the_secret_there_is_nothing_to_see() {
        let fake = Fake::new();
        let ctx = context(&fake);
        for path in [
            "/",
            "/status.json",
            "/WRONG/",
            "/WRONG/status.json",
            "/secret/",
            "/SECRE/",
            "/SECRETX/",
            "//",
            "/SECRET/../",
        ] {
            let reply = respond(&ctx, &get(path));
            assert!(
                reply.status == 404 || reply.status == 308,
                "{path}: {}",
                reply.status
            );
            assert!(!body(&reply).contains("<svg"), "{path}");
        }
        assert_eq!(
            fake.invites.load(Ordering::SeqCst),
            0,
            "a stranger must not be able to replace the code"
        );
    }

    #[test]
    fn a_request_for_another_host_is_refused_whatever_it_asks() {
        let fake = Fake::new();
        let ctx = context(&fake);
        for host in [
            Some("evil.example"),
            Some("evil.example:4321"),
            Some("127.0.0.1"),
            Some("127.0.0.1:9999"),
            Some(""),
            None,
        ] {
            let mut request = get("/SECRET/status.json");
            request.host = host.map(str::to_owned);
            assert_eq!(respond(&ctx, &request).status, 400, "{host:?}");
        }
        // Both names for this computer are fine.
        let mut localhost = get("/SECRET/status.json");
        localhost.host = Some("localhost:4321".into());
        assert_eq!(respond(&ctx, &localhost).status, 200);
    }

    #[test]
    fn a_missing_slash_is_added_so_the_pages_own_files_are_found() {
        let fake = Fake::new();
        let reply = respond(&context(&fake), &get("/SECRET"));
        assert_eq!(reply.status, 308);
        assert_eq!(reply.location.as_deref(), Some("/SECRET/"));
    }

    // ── Changing things ──────────────────────────────────────────────

    #[test]
    fn a_post_from_another_site_changes_nothing() {
        let fake = Fake::new();
        let ctx = context(&fake);
        for origin in [
            "http://evil.example",
            "https://127.0.0.1:4321",
            "null",
            "http://127.0.0.1:9999",
            "http://127.0.0.1:4321.evil.example",
        ] {
            for path in ["/SECRET/quit", "/SECRET/autostart?on=1", "/SECRET/new"] {
                let mut request = post(path);
                request.origin = Some(origin.into());
                assert_eq!(respond(&ctx, &request).status, 403, "{origin} {path}");
            }
        }
        assert!(!fake.quit.load(Ordering::SeqCst));
        assert!(!fake.autostart.load(Ordering::SeqCst));
    }

    #[test]
    fn the_buttons_on_the_page_work() {
        let fake = Fake::new();
        let ctx = context(&fake);

        assert_eq!(respond(&ctx, &post("/SECRET/autostart?on=1")).status, 303);
        assert!(fake.autostart.load(Ordering::SeqCst));
        assert_eq!(respond(&ctx, &post("/SECRET/autostart?on=0")).status, 303);
        assert!(!fake.autostart.load(Ordering::SeqCst));

        assert_eq!(respond(&ctx, &post("/SECRET/new")).status, 303);

        let reply = respond(&ctx, &post("/SECRET/quit"));
        assert!(fake.quit.load(Ordering::SeqCst));
        assert!(body(&reply).contains("Telepad has quit"));
    }

    #[test]
    fn the_toggle_says_what_it_would_do() {
        let fake = Fake::new();
        let ctx = context(&fake);
        assert!(body(&respond(&ctx, &get("/SECRET/"))).contains("action=\"autostart?on=1\""));
        fake.autostart.store(true, Ordering::SeqCst);
        assert!(body(&respond(&ctx, &get("/SECRET/"))).contains("action=\"autostart?on=0\""));
    }

    #[test]
    fn when_starting_at_login_cannot_be_changed_the_page_says_why() {
        let fake = Fake::new();
        fake.failing_autostart.store(true, Ordering::SeqCst);
        let reply = respond(&context(&fake), &post("/SECRET/autostart?on=1"));
        assert_eq!(reply.status, 200);
        assert!(body(&reply).contains("the disk is full"));
    }

    #[test]
    fn other_methods_are_not_allowed_and_unknown_files_are_not_found() {
        let fake = Fake::new();
        let ctx = context(&fake);
        let mut put = get("/SECRET/");
        put.method = Method::Other;
        assert_eq!(respond(&ctx, &put).status, 405);
        assert_eq!(respond(&ctx, &get("/SECRET/etc/passwd")).status, 404);
        assert_eq!(
            respond(&ctx, &get("/SECRET/quit")).status,
            404,
            "quitting is not something a link can do"
        );
    }

    // ── The real thing, over a socket ────────────────────────────────

    fn http(port: u16, request: &str) -> String {
        let mut stream = TcpStream::connect(("127.0.0.1", port)).unwrap();
        stream.set_read_timeout(Some(TEST_PATIENCE)).unwrap();
        stream.write_all(request.as_bytes()).unwrap();
        let mut response = String::new();
        let _ = stream.read_to_string(&mut response);
        response
    }

    fn port_of(panel: &Panel) -> u16 {
        panel
            .url()
            .split(':')
            .nth(2)
            .unwrap()
            .split('/')
            .next()
            .unwrap()
            .parse()
            .unwrap()
    }

    #[test]
    fn served_over_a_socket_the_page_is_locked_down() {
        let fake = Fake::new();
        let panel = Panel::start(
            fake.clone(),
            Identity {
                name: "PC".into(),
                fingerprint: "AAAA".into(),
                log_file: None,
            },
        )
        .unwrap();
        let port = port_of(&panel);
        let path = panel
            .url()
            .split(&format!("127.0.0.1:{port}"))
            .nth(1)
            .unwrap()
            .to_owned();
        assert!(
            panel.url().starts_with("http://127.0.0.1:"),
            "{}",
            panel.url()
        );
        assert!(path.len() > 20, "the secret is long: {path}");

        let page = http(
            port,
            &format!("GET {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: close\r\n\r\n"),
        );
        assert!(page.starts_with("HTTP/1.1 200"), "{page}");
        let lower = page.to_ascii_lowercase();
        assert!(lower.contains("content-security-policy: default-src 'none'"));
        assert!(lower.contains("cache-control: no-store"));
        assert!(lower.contains("x-content-type-options: nosniff"));
        assert!(lower.contains("referrer-policy: no-referrer"));
        assert!(page.contains("<svg"));

        // A page on another site that has made the browser connect here through its own name.
        let rebound = http(
            port,
            &format!(
                "GET {path} HTTP/1.1\r\nHost: evil.example:{port}\r\nConnection: close\r\n\r\n"
            ),
        );
        assert!(rebound.starts_with("HTTP/1.1 400"), "{rebound}");
        assert!(!rebound.contains("<svg"));

        // No secret, no page.
        let guessed = http(
            port,
            &format!(
                "GET /status.json HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: close\r\n\r\n"
            ),
        );
        assert!(guessed.starts_with("HTTP/1.1 404"), "{guessed}");
    }

    #[test]
    fn a_form_post_over_a_socket_quits_only_when_it_comes_from_the_page() {
        let fake = Fake::new();
        let panel = Panel::start(
            fake.clone(),
            Identity {
                name: "PC".into(),
                fingerprint: "AAAA".into(),
                log_file: None,
            },
        )
        .unwrap();
        let port = port_of(&panel);
        let path = panel
            .url()
            .split(&format!("127.0.0.1:{port}"))
            .nth(1)
            .unwrap()
            .to_owned();

        let foreign = http(
            port,
            &format!("POST {path}quit HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nOrigin: http://evil.example\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"),
        );
        assert!(foreign.starts_with("HTTP/1.1 403"), "{foreign}");
        assert!(!fake.quit.load(Ordering::SeqCst));

        let own = http(
            port,
            &format!("POST {path}quit HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nOrigin: http://127.0.0.1:{port}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"),
        );
        assert!(own.starts_with("HTTP/1.1 200"), "{own}");
        assert!(fake.quit.load(Ordering::SeqCst));
    }

    #[test]
    fn each_start_has_its_own_secret() {
        let fake = Fake::new();
        let identity = || Identity {
            name: "PC".into(),
            fingerprint: "AAAA".into(),
            log_file: None,
        };
        let a = Panel::start(fake.clone(), identity()).unwrap();
        let b = Panel::start(fake.clone(), identity()).unwrap();
        assert_ne!(a.url(), b.url());
        assert_ne!(make_secret(), make_secret());
    }
}
