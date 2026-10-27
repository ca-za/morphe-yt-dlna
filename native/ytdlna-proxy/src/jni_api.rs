//! JNI entry points for app.ytdlna.extension.NativeProxy.

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong, JNI_TRUE};
use jni::JNIEnv;

use crate::{log, registry, server, upstream};

fn string(env: &mut JNIEnv, value: &JString) -> String {
    env.get_string(value).map(Into::into).unwrap_or_default()
}

/// Runs `f`, turning a panic into `fallback` so it never unwinds into the JVM.
fn guarded<T>(fallback: T, f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or_else(|_| {
        crate::error!("Native proxy panicked");
        fallback
    })
}

/// Starts the server. Returns the port, or -1 on failure.
#[no_mangle]
pub extern "system" fn Java_app_ytdlna_extension_NativeProxy_nativeStart(
    _env: JNIEnv,
    _class: JClass,
    preferred_port: jint,
) -> jint {
    guarded(-1, || match server::start(preferred_port as u16) {
        Ok(port) => port as jint,
        Err(e) => {
            crate::error!("Could not start native proxy: {e}");
            -1
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_app_ytdlna_extension_NativeProxy_nativeSetUserAgent(
    mut env: JNIEnv,
    _class: JClass,
    user_agent: JString,
) {
    let user_agent = string(&mut env, &user_agent);
    guarded((), || upstream::set_user_agent(user_agent));
}

#[no_mangle]
pub extern "system" fn Java_app_ytdlna_extension_NativeProxy_nativeSetDebug(
    _env: JNIEnv,
    _class: JClass,
    enabled: jboolean,
) {
    log::set_debug(enabled == JNI_TRUE);
}

#[no_mangle]
pub extern "system" fn Java_app_ytdlna_extension_NativeProxy_nativePutText(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    mime: JString,
    body: JString,
) {
    let path = string(&mut env, &path);
    let mime = string(&mut env, &mime);
    let body = string(&mut env, &body);
    guarded((), || registry::put_text(path, mime, body.into_bytes()));
}

#[no_mangle]
pub extern "system" fn Java_app_ytdlna_extension_NativeProxy_nativePutStream(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    mime: JString,
    url: JString,
    length: jlong,
) {
    let path = string(&mut env, &path);
    let mime = string(&mut env, &mime);
    let url = string(&mut env, &url);
    guarded((), || registry::put_stream(path, mime, url, length.max(0) as u64));
}

#[no_mangle]
pub extern "system" fn Java_app_ytdlna_extension_NativeProxy_nativeRemovePrefix(
    mut env: JNIEnv,
    _class: JClass,
    prefix: JString,
) {
    let prefix = string(&mut env, &prefix);
    guarded((), || registry::remove_prefix(&prefix));
}
