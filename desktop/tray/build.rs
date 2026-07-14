#[cfg(windows)]
fn main() {
    use winres::{VersionInfo, WindowsResource};

    let version = std::env::var("CARGO_PKG_VERSION").expect("package version");
    let mut parts = version
        .split(['.', '-'])
        .take(4)
        .map(|part| part.parse::<u64>().unwrap_or(0))
        .collect::<Vec<_>>();
    parts.resize(4, 0);
    let numeric_version = (parts[0] << 48) | (parts[1] << 32) | (parts[2] << 16) | parts[3];

    let mut resource = WindowsResource::new();
    resource
        .set_icon("icons/icon.ico")
        .set("FileDescription", "Hermes Relay menu-only Windows systray")
        .set("ProductName", "Hermes Relay CLI + Systray")
        .set("OriginalFilename", "hermes-relay-tray.exe")
        .set("FileVersion", &version)
        .set("ProductVersion", &version)
        .set_version_info(VersionInfo::FILEVERSION, numeric_version)
        .set_version_info(VersionInfo::PRODUCTVERSION, numeric_version);
    resource.compile().expect("compile Windows resources");
}

#[cfg(not(windows))]
fn main() {}
