//! Test-only (feature `testing`, never in an Android build): Discord's voice server in its MLS
//! role, the *external sender*, so the JVM tests can run a real DAVE join through
//! `VoiceConnection` with davey on both ends. Modelled on davey's own `TestExternalSender`
//! (tests/unit/session.rs), which is ported from Discord's libdave tests.

use jni::objects::{JByteArray, JClass};
use jni::sys::{jbyteArray, jint, jlong};
use jni::JNIEnv;
use openmls::prelude::{tls_codec::Serialize, *};
use openmls_basic_credential::SignatureKeyPair;
use openmls_rust_crypto::OpenMlsRustCrypto;

struct ExternalSenderSim {
    provider: OpenMlsRustCrypto,
    group_id: GroupId,
    signer: SignatureKeyPair,
    external_sender: ExternalSender,
}

fn sim<'a>(handle: jlong) -> &'a ExternalSenderSim {
    unsafe { &*(handle as *const ExternalSenderSim) }
}

fn out(env: &JNIEnv, data: &[u8]) -> jbyteArray {
    env.byte_array_from_slice(data).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_TestExternalSender_nativeNew(_env: JNIEnv, _c: JClass, group_id: jlong) -> jlong {
    let ciphersuite = Ciphersuite::MLS_128_DHKEMP256_AES128GCM_SHA256_P256;
    let signer = SignatureKeyPair::new(ciphersuite.signature_algorithm()).unwrap();
    let external_sender = ExternalSender::new(
        signer.public().into(),
        BasicCredential::new(vec![0x00, 0x01, 0x01, 0x00]).into(),
    );
    let sim = ExternalSenderSim {
        provider: OpenMlsRustCrypto::default(),
        group_id: GroupId::from_slice(&(group_id as u64).to_be_bytes()),
        signer,
        external_sender,
    };
    Box::into_raw(Box::new(sim)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_TestExternalSender_nativeDestroy(_env: JNIEnv, _c: JClass, handle: jlong) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut ExternalSenderSim) });
    }
}

/// The payload of dave_mls_external_sender_package (25).
#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_TestExternalSender_nativePackage(env: JNIEnv, _c: JClass, handle: jlong) -> jbyteArray {
    out(&env, &sim(handle).external_sender.tls_serialize_detached().unwrap())
}

/// The proposals part of an append dave_mls_proposals (27) adding the owner of `key_package`
/// to the group at `epoch` (the payload without its leading operation-type byte).
#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_TestExternalSender_nativeProposeAdd(
    env: JNIEnv,
    _c: JClass,
    handle: jlong,
    epoch: jlong,
    key_package: JByteArray,
) -> jbyteArray {
    let s = sim(handle);
    let bytes = env.convert_byte_array(&key_package).unwrap();
    let key_package = KeyPackageIn::tls_deserialize_exact_bytes(&bytes)
        .unwrap()
        .validate(s.provider.crypto(), ProtocolVersion::Mls10)
        .unwrap();
    let message = ExternalProposal::new_add::<OpenMlsRustCrypto>(
        key_package,
        s.group_id.clone(),
        GroupEpoch::from(epoch as u64),
        &s.signer,
        SenderExtensionIndex::new(0),
    )
    .unwrap();
    out(&env, &VLBytes::new(message.tls_serialize_detached().unwrap()).tls_serialize_detached().unwrap())
}

/// Length of the first MLS message in `data`. The voice server splits a client's
/// dave_mls_commit_welcome (28) this way into the commit and the optional welcome.
#[no_mangle]
pub extern "system" fn Java_com_wristcord_wear_voice_TestExternalSender_nativeFirstMessageLength(env: JNIEnv, _c: JClass, data: JByteArray) -> jint {
    let bytes = env.convert_byte_array(&data).unwrap_or_default();
    match MlsMessageIn::tls_deserialize_bytes(&bytes) {
        Ok((_, rest)) => (bytes.len() - rest.len()) as jint,
        Err(_) => -1,
    }
}
