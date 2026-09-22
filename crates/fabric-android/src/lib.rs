//! Android JNI adapter for the shared portable fabric runtime.
use arachne_runtime::{
    MAX_REQUEST, cancel, close, create, create_lan, create_nearby, create_relay, create_tor,
    create_wan, create_wan_only, describe, execute, execute_stored,
};
use jni::{
    JNIEnv,
    objects::{JByteArray, JObject, JString},
    sys::{jboolean, jbyteArray, jint, jlong, jobjectArray, jstring},
};
use std::panic::{AssertUnwindSafe, catch_unwind};
#[cfg(any(
    test,
    all(
        target_os = "android",
        any(debug_assertions, feature = "iroh-diagnostics")
    )
))]
mod diagnostics;

fn boundary<T: Default>(
    env: &mut JNIEnv,
    operation: impl FnOnce(&mut JNIEnv) -> Result<T, String>,
) -> T {
    match catch_unwind(AssertUnwindSafe(|| operation(env))) {
        Ok(Ok(value)) => value,
        outcome => {
            let message = match outcome {
                Ok(Err(message)) => message,
                _ => "native node operation panicked".into(),
            };
            // Preserve an existing JVM exception (for example allocation failure).
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new("java/lang/IllegalStateException", message);
            }
            T::default()
        }
    }
}

fn init_transport_diagnostics() {
    #[cfg(all(
        target_os = "android",
        any(debug_assertions, feature = "iroh-diagnostics")
    ))]
    diagnostics::init();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_arachne_atak_FabricNative_create(
    mut env: JNIEnv,
    _: JObject,
    secret: JByteArray,
    relay_only: jboolean,
    lan_lookup: jboolean,
    local_only: jboolean,
) -> jlong {
    boundary(&mut env, |env| {
        if env.get_array_length(&secret).map_err(|e| e.to_string())? != 32 {
            return Err("endpoint credential must be 32 bytes".into());
        }
        let bytes =
            zeroize::Zeroizing::new(env.convert_byte_array(&secret).map_err(|e| e.to_string())?);
        let seed = bytes
            .as_slice()
            .try_into()
            .map_err(|_| "invalid endpoint credential")?;
        init_transport_diagnostics();
        if local_only != 0 {
            create_nearby(seed)
        } else if relay_only != 0 {
            create_relay(seed)
        } else if lan_lookup != 0 {
            create_wan(seed)
        } else {
            create_wan_only(seed)
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_arachne_atak_FabricNative_createTor(
    mut env: JNIEnv,
    _: JObject,
    secret: JByteArray,
) -> jlong {
    boundary(&mut env, |env| {
        if env.get_array_length(&secret).map_err(|e| e.to_string())? != 32 {
            return Err("endpoint credential must be 32 bytes".into());
        }
        let bytes =
            zeroize::Zeroizing::new(env.convert_byte_array(&secret).map_err(|e| e.to_string())?);
        let seed = bytes
            .as_slice()
            .try_into()
            .map_err(|_| "invalid endpoint credential")?;
        init_transport_diagnostics();
        create_tor(seed)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_arachne_atak_FabricNative_createProfile(
    mut env: JNIEnv,
    _: JObject,
    secret: JByteArray,
    profile: jint,
) -> jlong {
    boundary(&mut env, |env| {
        if env.get_array_length(&secret).map_err(|e| e.to_string())? != 32 {
            return Err("endpoint credential must be 32 bytes".into());
        }
        let bytes =
            zeroize::Zeroizing::new(env.convert_byte_array(&secret).map_err(|e| e.to_string())?);
        let seed = bytes
            .as_slice()
            .try_into()
            .map_err(|_| "invalid endpoint credential")?;
        init_transport_diagnostics();
        match profile {
            0 => create_wan(seed),
            1 => create(Some(seed)),
            2 => create_lan(seed),
            3 => create_wan_only(seed),
            4 => create_relay(seed),
            _ => Err("invalid Iroh transport profile".into()),
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_arachne_atak_FabricNative_describe(
    mut env: JNIEnv,
    _: JObject,
    handle: jlong,
) -> jstring {
    boundary(&mut env, |env| {
        env.new_string(describe(handle)?)
            .map(|s| s.into_raw())
            .map_err(|e| e.to_string())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_arachne_atak_FabricNative_close(
    mut env: JNIEnv,
    _: JObject,
    handle: jlong,
) {
    boundary(&mut env, |_| close(handle));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_arachne_atak_FabricNative_cancel(
    mut env: JNIEnv,
    _: JObject,
    handle: jlong,
) {
    boundary(&mut env, |_| cancel(handle));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_arachne_atak_FabricNative_execute(
    mut env: JNIEnv,
    _: JObject,
    handle: jlong,
    request: JByteArray,
) -> jbyteArray {
    boundary(&mut env, |env| {
        let len = env.get_array_length(&request).map_err(|e| e.to_string())?;
        if len as usize > MAX_REQUEST {
            return Err("request exceeds limit".into());
        }
        let bytes = env
            .convert_byte_array(&request)
            .map_err(|e| e.to_string())?;
        let reply = execute(handle, &bytes)?;
        env.byte_array_from_slice(&reply)
            .map(|array| array.into_raw())
            .map_err(|e| e.to_string())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_arachne_atak_FabricNative_inspectInvitation(
    mut env: JNIEnv,
    _: JObject,
    request: JByteArray,
) -> jbyteArray {
    boundary(&mut env, |env| {
        if env.get_array_length(&request).map_err(|e| e.to_string())? as usize > MAX_REQUEST {
            return Err("request exceeds limit".into());
        }
        let request = env
            .convert_byte_array(&request)
            .map_err(|e| e.to_string())?;
        let reply = arachne_runtime::inspect_invitation(&request)?;
        env.byte_array_from_slice(&reply)
            .map(|array| array.into_raw())
            .map_err(|e| e.to_string())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_arachne_atak_FabricNative_executeStored(
    mut env: JNIEnv,
    _: JObject,
    handle: jlong,
    metadata: JByteArray,
    snapshot: JByteArray,
) -> jobjectArray {
    boundary(&mut env, |env| {
        if env.get_array_length(&metadata).map_err(|e| e.to_string())? as usize > MAX_REQUEST
            || env.get_array_length(&snapshot).map_err(|e| e.to_string())? as usize
                > arachne_security::MAX_SEALED_BUNDLE
        {
            return Err("stored request exceeds limit".into());
        }
        let metadata = env
            .convert_byte_array(&metadata)
            .map_err(|e| e.to_string())?;
        let snapshot = env
            .convert_byte_array(&snapshot)
            .map_err(|e| e.to_string())?;
        let reply = execute_stored(handle, &metadata, &snapshot)?;
        let result = env
            .new_object_array(2, "[B", JObject::null())
            .map_err(|e| e.to_string())?;
        for (index, bytes) in reply.iter().enumerate() {
            let array = env
                .byte_array_from_slice(bytes)
                .map_err(|e| e.to_string())?;
            env.set_object_array_element(&result, index as i32, array)
                .map_err(|e| e.to_string())?;
        }
        Ok(result.into_raw())
    })
}

fn storage_root(
    env: &mut JNIEnv,
    root: &JByteArray,
) -> Result<zeroize::Zeroizing<Vec<u8>>, String> {
    if env.get_array_length(root).map_err(|e| e.to_string())? != 32 {
        return Err("storage credential must be 32 bytes".into());
    }
    Ok(zeroize::Zeroizing::new(
        env.convert_byte_array(root).map_err(|e| e.to_string())?,
    ))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_arachne_atak_FabricNative_enableRecords(
    mut env: JNIEnv,
    _: JObject,
    handle: jlong,
    path: JString,
    root: JByteArray,
) {
    boundary(&mut env, |env| {
        let root = storage_root(env, &root)?;
        let path: String = env.get_string(&path).map_err(|e| e.to_string())?.into();
        arachne_runtime::enable_record_storage(
            handle,
            std::path::Path::new(&path),
            root.as_slice().try_into().unwrap(),
        )
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_arachne_atak_FabricNative_restoreRecords(
    mut env: JNIEnv,
    _: JObject,
    handle: jlong,
    path: JString,
    root: JByteArray,
    workspace: JByteArray,
) -> jstring {
    boundary(&mut env, |env| {
        let root = storage_root(env, &root)?;
        if env
            .get_array_length(&workspace)
            .map_err(|e| e.to_string())?
            != 32
        {
            return Err("workspace must be 32 bytes".into());
        }
        let workspace = env
            .convert_byte_array(&workspace)
            .map_err(|e| e.to_string())?;
        let path: String = env.get_string(&path).map_err(|e| e.to_string())?.into();
        let value = arachne_runtime::restore_record_storage(
            handle,
            std::path::Path::new(&path),
            root.as_slice().try_into().unwrap(),
            workspace.as_slice().try_into().unwrap(),
        )?;
        env.new_string(value.to_string())
            .map(|s| s.into_raw())
            .map_err(|e| e.to_string())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_arachne_atak_FabricNative_saveCandidate(
    mut env: JNIEnv,
    _: JObject,
    handle: jlong,
    token: JByteArray,
) {
    boundary(&mut env, |env| {
        if env.get_array_length(&token).map_err(|e| e.to_string())? != 37 {
            return Err("invalid candidate token length".into());
        }
        let token = env.convert_byte_array(&token).map_err(|e| e.to_string())?;
        arachne_runtime::save_candidate(handle, &token)
    });
}

/// Blocks the calling JVM thread. Call only from the session's dedicated waiter
/// thread, never from the session worker or the UI thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_arachne_atak_FabricNative_waitForWork(
    mut env: JNIEnv,
    _: JObject,
    handle: jlong,
) -> jboolean {
    boundary(&mut env, |_| {
        arachne_runtime::wait_for_work(handle).map(|work| work as jboolean)
    })
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_datafabric_atak_FabricNative_create(
    env: JNIEnv,
    object: JObject,
    secret: JByteArray,
    relay_only: jboolean,
    lan_lookup: jboolean,
    local_only: jboolean,
) -> jlong {
    Java_dev_arachne_atak_FabricNative_create(
        env, object, secret, relay_only, lan_lookup, local_only,
    )
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_datafabric_atak_FabricNative_describe(
    env: JNIEnv,
    object: JObject,
    handle: jlong,
) -> jstring {
    Java_dev_arachne_atak_FabricNative_describe(env, object, handle)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_datafabric_atak_FabricNative_close(
    env: JNIEnv,
    object: JObject,
    handle: jlong,
) {
    Java_dev_arachne_atak_FabricNative_close(env, object, handle)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_datafabric_atak_FabricNative_cancel(
    env: JNIEnv,
    object: JObject,
    handle: jlong,
) {
    Java_dev_arachne_atak_FabricNative_cancel(env, object, handle)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_datafabric_atak_FabricNative_execute(
    env: JNIEnv,
    object: JObject,
    handle: jlong,
    request: JByteArray,
) -> jbyteArray {
    Java_dev_arachne_atak_FabricNative_execute(env, object, handle, request)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_datafabric_atak_FabricNative_inspectInvitation(
    env: JNIEnv,
    object: JObject,
    request: JByteArray,
) -> jbyteArray {
    Java_dev_arachne_atak_FabricNative_inspectInvitation(env, object, request)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_datafabric_atak_FabricNative_executeStored(
    env: JNIEnv,
    object: JObject,
    handle: jlong,
    metadata: JByteArray,
    snapshot: JByteArray,
) -> jobjectArray {
    Java_dev_arachne_atak_FabricNative_executeStored(env, object, handle, metadata, snapshot)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_datafabric_atak_FabricNative_enableRecords(
    env: JNIEnv,
    object: JObject,
    handle: jlong,
    path: JString,
    root: JByteArray,
) {
    Java_dev_arachne_atak_FabricNative_enableRecords(env, object, handle, path, root)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_datafabric_atak_FabricNative_restoreRecords(
    env: JNIEnv,
    object: JObject,
    handle: jlong,
    path: JString,
    root: JByteArray,
    workspace: JByteArray,
) -> jstring {
    Java_dev_arachne_atak_FabricNative_restoreRecords(env, object, handle, path, root, workspace)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_datafabric_atak_FabricNative_saveCandidate(
    env: JNIEnv,
    object: JObject,
    handle: jlong,
    token: JByteArray,
) {
    Java_dev_arachne_atak_FabricNative_saveCandidate(env, object, handle, token)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_datafabric_atak_FabricNative_waitForWork(
    env: JNIEnv,
    object: JObject,
    handle: jlong,
) -> jboolean {
    Java_dev_arachne_atak_FabricNative_waitForWork(env, object, handle)
}
