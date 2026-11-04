//! JNI exports for `com.wristcord.wear.voice.NativeDave`.
//!
//! One `Handle` per voice connection. Every call locks it, because MLS messages arrive on the
//! voice gateway thread while frames are encrypted and decrypted on the audio threads. Panics
//! are caught at the boundary so a malformed message can only fail the call, never the app.

use std::num::NonZeroU16;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Mutex;

use davey::{Codec, DaveSession, MediaType, ProposalsOperationType, DAVE_PROTOCOL_VERSION};
use jni::objects::{JByteArray, JClass, JLongArray};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

#[cfg(feature = "testing")]
mod testing;

struct Inner {
    session: DaveSession,
    last_error: String,
}

struct Handle(Mutex<Inner>);

fn handle<'a>(ptr: jlong) -> Option<&'a Handle> {
    if ptr == 0 {
        None
    } else {
        // SAFETY: the pointer came from Box::into_raw in nativeCreate and Kotlin frees it once.
        Some(unsafe { &*(ptr as *const Handle) })
    }
}

fn with<T>(ptr: jlong, fallback: T, f: impl FnOnce(&mut Inner) -> T) -> T {
    let Some(h) = handle(ptr) else { return fallback };
    let mut guard = match h.0.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    match catch_unwind(AssertUnwindSafe(|| f(&mut guard))) {
        Ok(v) => v,
        Err(_) => {
            guard.last_error = "panic inside davey".into();
            fallback
        }
    }
}

fn bytes(env: &JNIEnv, array: &JByteArray) -> Vec<u8> {
    env.convert_byte_array(array).unwrap_or_default()
}

fn longs(env: &mut JNIEnv, array: &JLongArray) -> Option<Vec<u64>> {
    if array.is_null() {
        return None;
    }
    let len = env.get_array_length(array).ok()? as usize;
    let mut buf = vec![0i64; len];
    env.get_long_array_region(array, 0, &mut buf).ok()?;
    Some(buf.into_iter().map(|v| v as u64).collect())
}

fn out(env: &JNIEnv, data: &[u8]) -> jbyteArray {
    env.byte_array_from_slice(data).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn version(v: jint) -> Option<NonZeroU16> {
    NonZeroU16::new(u16::try_from(v).ok()?)
}

#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeDave_nativeMaxProtocolVersion(_env: JNIEnv, _c: JClass) -> jint {
    DAVE_PROTOCOL_VERSION as jint
}

#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeDave_nativeCreate(
    _env: JNIEnv,
    _c: JClass,
    protocol_version: jint,
    user_id: jlong,
    channel_id: jlong,
) -> jlong {
    let Some(pv) = version(protocol_version) else { return 0 };
    match catch_unwind(|| DaveSession::new(pv, user_id as u64, channel_id as u64, None)) {
        Ok(Ok(session)) => Box::into_raw(Box::new(Handle(Mutex::new(Inner { session, last_error: String::new() })))) as jlong,
        _ => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeDave_nativeDestroy(_env: JNIEnv, _c: JClass, ptr: jlong) {
    if ptr != 0 {
        // SAFETY: matches Box::into_raw in nativeCreate; Kotlin guarantees a single destroy.
        drop(unsafe { Box::from_raw(ptr as *mut Handle) });
    }
}

#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeDave_nativeReinit(
    _env: JNIEnv,
    _c: JClass,
    ptr: jlong,
    protocol_version: jint,
    user_id: jlong,
    channel_id: jlong,
) -> jboolean {
    with(ptr, JNI_FALSE, |s| {
        let Some(pv) = version(protocol_version) else { return JNI_FALSE };
        match s.session.reinit(pv, user_id as u64, channel_id as u64, None) {
            Ok(()) => JNI_TRUE,
            Err(e) => { s.last_error = e.to_string(); JNI_FALSE }
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeDave_nativeReset(_env: JNIEnv, _c: JClass, ptr: jlong) -> jboolean {
    with(ptr, JNI_FALSE, |s| match s.session.reset() {
        Ok(()) => JNI_TRUE,
        Err(e) => { s.last_error = e.to_string(); JNI_FALSE }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeDave_nativeSetExternalSender(
    env: JNIEnv,
    _c: JClass,
    ptr: jlong,
    data: JByteArray,
) -> jboolean {
    let data = bytes(&env, &data);
    with(ptr, JNI_FALSE, |s| match s.session.set_external_sender(&data) {
        Ok(()) => JNI_TRUE,
        Err(e) => { s.last_error = e.to_string(); JNI_FALSE }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeDave_nativeCreateKeyPackage(env: JNIEnv, _c: JClass, ptr: jlong) -> jbyteArray {
    let result = with(ptr, None, |s| match s.session.create_key_package() {
        Ok(kp) => Some(kp),
        Err(e) => { s.last_error = e.to_string(); None }
    });
    match result {
        Some(kp) => out(&env, &kp),
        None => std::ptr::null_mut(),
    }
}

/// Returns `[status][commit..welcome]`: status 0 = nothing to send, 1 = commit (and welcome,
/// concatenated the way the voice gateway expects for opcode 28), 2 = error.
#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeDave_nativeProcessProposals(
    mut env: JNIEnv,
    _c: JClass,
    ptr: jlong,
    operation_type: jint,
    data: JByteArray,
    expected_user_ids: JLongArray,
) -> jbyteArray {
    let data = bytes(&env, &data);
    let expected = longs(&mut env, &expected_user_ids);
    let op = if operation_type == 1 { ProposalsOperationType::REVOKE } else { ProposalsOperationType::APPEND };
    let result: Vec<u8> = with(ptr, vec![2u8], |s| match s.session.process_proposals(op, &data, expected.as_deref()) {
        Ok(Some(cw)) => {
            let mut v = Vec::with_capacity(1 + cw.commit.len() + cw.welcome.as_ref().map_or(0, |w| w.len()));
            v.push(1);
            v.extend_from_slice(&cw.commit);
            if let Some(w) = cw.welcome { v.extend_from_slice(&w); }
            v
        }
        Ok(None) => vec![0u8],
        Err(e) => { s.last_error = e.to_string(); vec![2u8] }
    });
    out(&env, &result)
}

#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeDave_nativeProcessWelcome(
    mut env: JNIEnv,
    _c: JClass,
    ptr: jlong,
    data: JByteArray,
    recognized_user_ids: JLongArray,
) -> jboolean {
    let data = bytes(&env, &data);
    let recognized = longs(&mut env, &recognized_user_ids);
    with(ptr, JNI_FALSE, |s| match s.session.process_welcome(&data, recognized.as_deref()) {
        Ok(()) => JNI_TRUE,
        Err(e) => { s.last_error = e.to_string(); JNI_FALSE }
    })
}

/// 1 = commit applied, prepare the transition; 0 = ignored; -1 = invalid.
#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeDave_nativeProcessCommit(
    env: JNIEnv,
    _c: JClass,
    ptr: jlong,
    data: JByteArray,
) -> jint {
    let data = bytes(&env, &data);
    with(ptr, -1, |s| match s.session.process_commit(&data) {
        Ok(true) => 1,
        Ok(false) => 0,
        Err(e) => { s.last_error = e.to_string(); -1 }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeDave_nativePrepareTransition(
    _env: JNIEnv,
    _c: JClass,
    ptr: jlong,
    transition_id: jint,
    protocol_version: jint,
) -> jboolean {
    with(ptr, JNI_FALSE, |s| match s.session.prepare_transition(transition_id as u16, protocol_version as u16) {
        Ok(()) => JNI_TRUE,
        Err(e) => { s.last_error = e.to_string(); JNI_FALSE }
    })
}

/// 1 = executed, 0 = no such pending transition, -1 = error.
#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeDave_nativeExecuteTransition(
    _env: JNIEnv,
    _c: JClass,
    ptr: jlong,
    transition_id: jint,
) -> jint {
    with(ptr, -1, |s| match s.session.execute_transition(transition_id as u16) {
        Ok(true) => 1,
        Ok(false) => 0,
        Err(e) => { s.last_error = e.to_string(); -1 }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeDave_nativeEncryptOpus(
    env: JNIEnv,
    _c: JClass,
    ptr: jlong,
    frame: JByteArray,
) -> jbyteArray {
    let frame = bytes(&env, &frame);
    let result = with(ptr, None, |s| match s.session.encrypt(MediaType::AUDIO, Codec::OPUS, &frame) {
        Ok(v) => Some(v.into_owned()),
        Err(e) => { s.last_error = e.to_string(); None }
    });
    match result {
        Some(v) => out(&env, &v),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeDave_nativeDecrypt(
    env: JNIEnv,
    _c: JClass,
    ptr: jlong,
    user_id: jlong,
    frame: JByteArray,
) -> jbyteArray {
    let frame = bytes(&env, &frame);
    let result = with(ptr, None, |s| match s.session.decrypt(user_id as u64, MediaType::AUDIO, &frame) {
        Ok(v) => Some(v),
        Err(e) => { s.last_error = e.to_string(); None }
    });
    match result {
        Some(v) => out(&env, &v),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeDave_nativeIsReady(_env: JNIEnv, _c: JClass, ptr: jlong) -> jboolean {
    with(ptr, JNI_FALSE, |s| if s.session.is_ready() { JNI_TRUE } else { JNI_FALSE })
}

#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeDave_nativeCanEncrypt(_env: JNIEnv, _c: JClass, ptr: jlong) -> jboolean {
    with(ptr, JNI_FALSE, |s| if s.session.can_encrypt() { JNI_TRUE } else { JNI_FALSE })
}

#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeDave_nativeCanPassthrough(_env: JNIEnv, _c: JClass, ptr: jlong, user_id: jlong) -> jboolean {
    with(ptr, JNI_FALSE, |s| if s.session.can_passthrough(user_id as u64) { JNI_TRUE } else { JNI_FALSE })
}

#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeDave_nativeSetPassthroughMode(
    _env: JNIEnv,
    _c: JClass,
    ptr: jlong,
    enabled: jboolean,
    expiry_seconds: jint,
) {
    with(ptr, (), |s| {
        let expiry = if expiry_seconds > 0 { Some(expiry_seconds as u32) } else { None };
        s.session.set_passthrough_mode(enabled != JNI_FALSE, expiry);
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeDave_nativeVoicePrivacyCode(env: JNIEnv, _c: JClass, ptr: jlong) -> jstring {
    let code = with(ptr, String::new(), |s| s.session.voice_privacy_code().unwrap_or("").to_string());
    env.new_string(code).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeDave_nativeLastError(env: JNIEnv, _c: JClass, ptr: jlong) -> jstring {
    let msg = with(ptr, String::new(), |s| std::mem::take(&mut s.last_error));
    env.new_string(msg).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}


/// XChaCha20-Poly1305 seal: returns ciphertext || 16-byte tag, or null on bad input.
#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeDave_nativeXChaChaSeal(
    env: JNIEnv,
    _c: JClass,
    key: JByteArray,
    nonce: JByteArray,
    aad: JByteArray,
    plaintext: JByteArray,
) -> jbyteArray {
    use chacha20poly1305::aead::{Aead, KeyInit, Payload};
    use chacha20poly1305::{XChaCha20Poly1305, XNonce};
    let (key, nonce, aad, pt) = (bytes(&env, &key), bytes(&env, &nonce), bytes(&env, &aad), bytes(&env, &plaintext));
    if key.len() != 32 || nonce.len() != 24 { return std::ptr::null_mut(); }
    let Ok(cipher) = XChaCha20Poly1305::new_from_slice(&key) else { return std::ptr::null_mut() };
    match cipher.encrypt(XNonce::from_slice(&nonce), Payload { msg: &pt, aad: &aad }) {
        Ok(ct) => out(&env, &ct),
        Err(_) => std::ptr::null_mut(),
    }
}

/// XChaCha20-Poly1305 open: input is ciphertext || tag; returns plaintext, or null if it does not verify.
#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeDave_nativeXChaChaOpen(
    env: JNIEnv,
    _c: JClass,
    key: JByteArray,
    nonce: JByteArray,
    aad: JByteArray,
    ciphertext: JByteArray,
) -> jbyteArray {
    use chacha20poly1305::aead::{Aead, KeyInit, Payload};
    use chacha20poly1305::{XChaCha20Poly1305, XNonce};
    let (key, nonce, aad, ct) = (bytes(&env, &key), bytes(&env, &nonce), bytes(&env, &aad), bytes(&env, &ciphertext));
    if key.len() != 32 || nonce.len() != 24 { return std::ptr::null_mut(); }
    let Ok(cipher) = XChaCha20Poly1305::new_from_slice(&key) else { return std::ptr::null_mut() };
    match cipher.decrypt(XNonce::from_slice(&nonce), Payload { msg: &ct, aad: &aad }) {
        Ok(pt) => out(&env, &pt),
        Err(_) => std::ptr::null_mut(),
    }
}
