//! Opus for the watch's calls through libopus itself (bundled and built by opusic-sys), for
//! class `com.wristcord.wear.voice.NativeOpus`. It replaced Android's MediaCodec Opus, which on
//! Wear OS 17 crashed with more than one codec busy and handed frames back in bursts: here one
//! packet in is one frame out, synchronously, on every Android version.

use std::os::raw::c_int;

use jni::objects::{JByteArray, JClass, JShortArray};
use jni::sys::{jbyteArray, jint, jlong};
use jni::JNIEnv;
use opusic_sys::*;

const RATE: i32 = 48_000;
/// 120 ms at 48 kHz, the longest frame Opus has: any packet decodes into this.
const MAX_FRAME: usize = 5_760;
/// 20 ms, the frame loss concealment fills.
const FRAME: c_int = 960;
const MAX_PACKET: usize = 1_500;

struct Decoder {
    state: *mut OpusDecoder,
    channels: usize,
}

struct Encoder {
    state: *mut OpusEncoder,
    channels: usize,
}

#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeOpus_nativeDecoderNew(_env: JNIEnv, _c: JClass, channels: jint) -> jlong {
    let mut error: c_int = 0;
    let state = unsafe { opus_decoder_create(RATE, channels, &mut error) };
    if state.is_null() || error != OPUS_OK {
        return 0;
    }
    Box::into_raw(Box::new(Decoder { state, channels: channels as usize })) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeOpus_nativeDecoderDestroy(_env: JNIEnv, _c: JClass, handle: jlong) {
    if handle != 0 {
        let d = unsafe { Box::from_raw(handle as *mut Decoder) };
        unsafe { opus_decoder_destroy(d.state) };
    }
}

/// Decodes `packet` into `out` (interleaved), or with a null packet conceals one lost 20 ms
/// frame. Returns samples per channel, or a negative libopus error.
#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeOpus_nativeDecode(
    env: JNIEnv,
    _c: JClass,
    handle: jlong,
    packet: JByteArray,
    out: JShortArray,
) -> jint {
    if handle == 0 {
        return OPUS_INVALID_STATE;
    }
    let d = unsafe { &*(handle as *const Decoder) };
    let capacity = env.get_array_length(&out).unwrap_or(0) as usize / d.channels;
    let frame = capacity.min(MAX_FRAME);
    if frame == 0 {
        return OPUS_BUFFER_TOO_SMALL;
    }
    let mut pcm = vec![0i16; frame * d.channels];
    let n = if packet.is_null() {
        unsafe { opus_decode(d.state, std::ptr::null(), 0, pcm.as_mut_ptr(), FRAME.min(frame as c_int), 0) }
    } else {
        let data = env.convert_byte_array(&packet).unwrap_or_default();
        unsafe { opus_decode(d.state, data.as_ptr(), data.len() as i32, pcm.as_mut_ptr(), frame as c_int, 0) }
    };
    if n > 0 && env.set_short_array_region(&out, 0, &pcm[..n as usize * d.channels]).is_err() {
        return OPUS_INTERNAL_ERROR;
    }
    n
}

/// A voice encoder: in-band FEC on (Discord's own clients use it), the speech hint, and a
/// middling complexity that a watch CPU keeps up with easily.
#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeOpus_nativeEncoderNew(_env: JNIEnv, _c: JClass, channels: jint, bitrate: jint) -> jlong {
    let mut error: c_int = 0;
    let state = unsafe { opus_encoder_create(RATE, channels, OPUS_APPLICATION_VOIP, &mut error) };
    if state.is_null() || error != OPUS_OK {
        return 0;
    }
    unsafe {
        opus_encoder_ctl(state, OPUS_SET_BITRATE_REQUEST, bitrate as opus_int32);
        opus_encoder_ctl(state, OPUS_SET_COMPLEXITY_REQUEST, 5 as c_int);
        opus_encoder_ctl(state, OPUS_SET_SIGNAL_REQUEST, OPUS_SIGNAL_VOICE);
        opus_encoder_ctl(state, OPUS_SET_INBAND_FEC_REQUEST, 1 as c_int);
        opus_encoder_ctl(state, OPUS_SET_PACKET_LOSS_PERC_REQUEST, 5 as c_int);
    }
    Box::into_raw(Box::new(Encoder { state, channels: channels as usize })) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeOpus_nativeEncoderDestroy(_env: JNIEnv, _c: JClass, handle: jlong) {
    if handle != 0 {
        let e = unsafe { Box::from_raw(handle as *mut Encoder) };
        unsafe { opus_encoder_destroy(e.state) };
    }
}

/// One frame of interleaved 16-bit PCM in, its Opus packet out (null on error).
#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_NativeOpus_nativeEncode(env: JNIEnv, _c: JClass, handle: jlong, pcm: JShortArray) -> jbyteArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let e = unsafe { &*(handle as *const Encoder) };
    let len = env.get_array_length(&pcm).unwrap_or(0) as usize;
    let mut samples = vec![0i16; len];
    if len == 0 || env.get_short_array_region(&pcm, 0, &mut samples).is_err() {
        return std::ptr::null_mut();
    }
    let mut packet = vec![0u8; MAX_PACKET];
    let n = unsafe {
        opus_encode(e.state, samples.as_ptr(), (len / e.channels) as c_int, packet.as_mut_ptr(), MAX_PACKET as opus_int32)
    };
    if n < 0 {
        return std::ptr::null_mut();
    }
    env.byte_array_from_slice(&packet[..n as usize]).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}
