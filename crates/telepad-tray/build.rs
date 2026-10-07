//! On Windows, gives the program its icon and its name in Explorer, in Task Manager's list of what starts at
//! login, and in the SmartScreen prompt. Elsewhere there is nothing to do.

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=../../packaging/icons/telepad.ico");
    #[cfg(windows)]
    resources::embed();
}

#[cfg(windows)]
mod resources {
    pub fn embed() {
        // The build script runs on the machine that builds; what it builds for is in the environment.
        if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows") {
            return;
        }
        let mut resource = winresource::WindowsResource::new();
        resource
            .set_icon("../../packaging/icons/telepad.ico")
            // Windows shows these in the places a person looks: the file's properties, the list of programs that
            // start at login ("Name" and "Publisher"), and the prompt before an unknown program runs.
            .set("ProductName", "Telepad")
            .set("FileDescription", "Telepad")
            .set("InternalName", "telepad")
            .set("OriginalFilename", "telepad.exe")
            .set("CompanyName", "Om Singh")
            .set("LegalCopyright", "Copyright (c) Om Singh. MIT License.");
        if let Err(error) = resource.compile() {
            let message =
                format!("could not add the icon and version information to telepad.exe: {error}");
            // A release must never go out without them, but a build on someone's own machine need not fail for it.
            if std::env::var_os("CI").is_some() {
                panic!("{message}");
            }
            println!("cargo:warning={message}");
        }
    }
}
