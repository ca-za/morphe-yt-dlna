//! JNI entry points for app.ytdlna.extension.NativeProxy.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::time::Duration;

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;

use crate::{events, registry, server, upstream};

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
pub extern "system" fn Java_app_ytdlna_extension_NativeProxy_nativeSetControlPoint(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    uuid: JString,
    user_agent: JString,
) {
    let name = string(&mut env, &name);
    let uuid = string(&mut env, &uuid);
    let user_agent = string(&mut env, &user_agent);
    guarded((), || events::set_control_point(&name, &uuid, &user_agent));
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

/// Subscribes to a renderer service. Returns the subscription token, or null on failure.
#[no_mangle]
pub extern "system" fn Java_app_ytdlna_extension_NativeProxy_nativeSubscribe(
    mut env: JNIEnv,
    _class: JClass,
    event_url: JString,
    callback_base: JString,
) -> jstring {
    let event_url = string(&mut env, &event_url);
    let callback_base = string(&mut env, &callback_base);
    let token = guarded(None, || match events::subscribe(&event_url, &callback_base) {
        Ok(token) => Some(token),
        Err(e) => {
            crate::info!("Subscribing to {event_url} failed: {e}");
            None
        }
    });
    match token.and_then(|t| env.new_string(t).ok()) {
        Some(s) => s.into_raw(),
        None => JObject::null().into_raw(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_ytdlna_extension_NativeProxy_nativeUnsubscribe(
    mut env: JNIEnv,
    _class: JClass,
    token: JString,
) {
    let token = string(&mut env, &token);
    guarded((), || events::unsubscribe(&token));
}

/// Waits for the next renderer event. Returns "token\nname\nvalue", or null on timeout.
#[no_mangle]
pub extern "system" fn Java_app_ytdlna_extension_NativeProxy_nativeWaitEvent(
    env: JNIEnv,
    _class: JClass,
    timeout_ms: jint,
) -> jstring {
    let event = guarded(None, || events::wait(Duration::from_millis(timeout_ms.max(0) as u64)));
    match event.and_then(|e| env.new_string(format!("{}\n{}\n{}", e.token, e.name, e.value)).ok()) {
        Some(s) => s.into_raw(),
        None => JObject::null().into_raw(),
    }
}
