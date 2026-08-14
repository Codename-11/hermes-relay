#![cfg(windows)]

// The tray is a binary crate. Include the module directly so its focused unit
// tests remain runnable independently of main.rs integration.
#[path = "../src/bounded_process.rs"]
mod bounded_process;
