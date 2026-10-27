//! Logging to logcat (tag "YtDlna") on Android, stderr elsewhere.

/// Debug logging is compiled in for debug builds only.
pub fn debug_enabled() -> bool {
    cfg!(debug_assertions)
}

#[cfg(target_os = "android")]
mod sys {
    use std::ffi::CString;
    use std::os::raw::{c_char, c_int};

    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }

    pub fn write(priority: i32, message: &str) {
        let tag = b"YtDlna\0";
        let text = CString::new(message.replace('\0', " ")).unwrap_or_default();
        unsafe {
            __android_log_write(priority, tag.as_ptr() as *const c_char, text.as_ptr());
        }
    }
}

#[cfg(not(target_os = "android"))]
mod sys {
    pub fn write(_priority: i32, message: &str) {
        eprintln!("YtDlna: {message}");
    }
}

const DEBUG_PRIORITY: i32 = 3;
const INFO_PRIORITY: i32 = 4;
const ERROR_PRIORITY: i32 = 6;

pub fn debug(message: &str) {
    if debug_enabled() {
        sys::write(DEBUG_PRIORITY, message);
    }
}

pub fn info(message: &str) {
    sys::write(INFO_PRIORITY, message);
}

pub fn error(message: &str) {
    sys::write(ERROR_PRIORITY, message);
}

#[macro_export]
macro_rules! debug {
    ($($arg:tt)*) => { if $crate::log::debug_enabled() { $crate::log::debug(&format!($($arg)*)) } };
}

#[macro_export]
macro_rules! info {
    ($($arg:tt)*) => { $crate::log::info(&format!($($arg)*)) };
}

#[macro_export]
macro_rules! error {
    ($($arg:tt)*) => { $crate::log::error(&format!($($arg)*)) };
}
