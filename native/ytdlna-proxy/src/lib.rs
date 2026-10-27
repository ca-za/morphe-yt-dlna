//! Stream proxy for the YT DLNA Morphe patch.
//!
//! The app registers what to serve (generated manifests and YouTube stream URLs) and the
//! renderer on the local network fetches it from here. YouTube stream URLs are bound to the
//! phone, so the renderer can not use them directly.
//!
//! Compared to a naive proxy this:
//! - keeps upstream connections to YouTube alive and reuses them (no TLS handshake per segment),
//! - supports keep-alive towards the renderer,
//! - fetches large ranges in chunks with a read-ahead buffer and retries failed chunks.

pub mod http;
pub mod log;
pub mod registry;
pub mod server;
pub mod upstream;

mod jni_api;
