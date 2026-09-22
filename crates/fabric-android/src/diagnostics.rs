//! Transport diagnostics in Android's log sink. The opt-in Iroh capture expires
//! after five minutes and includes addresses/path probes, never application data.
use std::time::Duration;

fn allowed(target: &str, name: &str, event: bool, instrumented: bool, age: Duration) -> bool {
    if target == "data_fabric_transport" {
        return true;
    }
    if !instrumented || age >= Duration::from_secs(300) {
        return false;
    }
    if !event {
        // Do not enable handle_message spans: their fields include datagrams.
        return (target == "iroh::socket" && name == "endpoint")
            || (target == "iroh::socket::remote_map::remote_state" && name == "RemoteStateActor");
    }
    matches!(
        target,
        "iroh::_events::qnt::init"
            | "iroh::_events::path::open"
            | "iroh::_events::path::selected"
            | "iroh::_events::path::abandoned"
            | "iroh::_events::path::set_status"
            | "iroh::socket::remote_map::remote_state"
            | "noq_proto::n0_nat_traversal"
    )
}

fn capture_filter<S: tracing::Subscriber>(
    instrumented: bool,
    age: impl Fn() -> Duration + Send + Sync + 'static,
) -> impl tracing_subscriber::Layer<S> {
    // A static metadata filter caches callsite interest and cannot enforce expiry.
    tracing_subscriber::filter::dynamic_filter_fn(move |metadata, _context| {
        allowed(
            metadata.target(),
            metadata.name(),
            metadata.is_event(),
            instrumented,
            age(),
        )
    })
}

#[cfg(target_os = "android")]
pub(super) use android::init;

#[cfg(target_os = "android")]
mod android {
    use std::{ffi::CString, io::Write, sync::Once};
    use tracing_subscriber::{
        filter::{LevelFilter, Targets},
        layer::SubscriberExt,
        util::SubscriberInitExt,
    };

    #[link(name = "log")]
    unsafe extern "C" {
        fn __android_log_write(
            priority: i32,
            tag: *const std::ffi::c_char,
            text: *const std::ffi::c_char,
        ) -> i32;
    }

    #[derive(Default)]
    struct AndroidLog(Vec<u8>);

    impl Write for AndroidLog {
        fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
            let available = 4000usize.saturating_sub(self.0.len());
            self.0
                .extend_from_slice(&bytes[..bytes.len().min(available)]);
            Ok(bytes.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    impl Drop for AndroidLog {
        fn drop(&mut self) {
            if let Ok(line) = CString::new(std::mem::take(&mut self.0)) {
                // Android copies the NUL-terminated bytes during this call.
                unsafe {
                    __android_log_write(4, c"Arachne".as_ptr(), line.as_ptr());
                }
            }
        }
    }

    pub(crate) fn init() {
        static INIT: Once = Once::new();
        INIT.call_once(|| {
            let instrumented = cfg!(feature = "iroh-diagnostics");
            let started = std::time::Instant::now();
            let mut targets =
                Targets::new().with_target("data_fabric_transport", LevelFilter::INFO);
            if instrumented {
                targets = targets
                    .with_target("iroh::_events::qnt", LevelFilter::DEBUG)
                    .with_target("iroh::_events::path", LevelFilter::DEBUG)
                    .with_target("iroh::socket::remote_map::remote_state", LevelFilter::TRACE)
                    .with_target("noq_proto::n0_nat_traversal", LevelFilter::TRACE)
                    .with_target("iroh::socket", LevelFilter::INFO);
                drop(AndroidLog(
                    b"IROH_DIAGNOSTICS_ENABLED window_seconds=300 addresses=true payloads=false"
                        .to_vec(),
                ));
            }
            let _ = tracing_subscriber::registry()
                .with(targets)
                .with(super::capture_filter(instrumented, move || {
                    started.elapsed()
                }))
                .with(
                    tracing_subscriber::fmt::layer()
                        .without_time()
                        .with_ansi(false)
                        .with_target(instrumented)
                        .with_writer(AndroidLog::default),
                )
                .try_init();
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn capture_expiry_is_checked_again_at_the_same_callsite() {
        use std::sync::{
            Arc,
            atomic::{AtomicU64, AtomicUsize, Ordering},
        };
        use tracing_subscriber::{Layer, layer::SubscriberExt};
        struct Count(Arc<AtomicUsize>);
        impl<S: tracing::Subscriber> Layer<S> for Count {
            fn on_event(
                &self,
                _: &tracing::Event<'_>,
                _: tracing_subscriber::layer::Context<'_, S>,
            ) {
                self.0.fetch_add(1, Ordering::Relaxed);
            }
        }
        let age = Arc::new(AtomicU64::new(0));
        let clock = age.clone();
        let events = Arc::new(AtomicUsize::new(0));
        let subscriber = tracing_subscriber::registry()
            .with(capture_filter(true, move || {
                Duration::from_secs(clock.load(Ordering::Relaxed))
            }))
            .with(Count(events.clone()));
        tracing::subscriber::with_default(subscriber, || {
            for seconds in [0, 300, 299] {
                age.store(seconds, Ordering::Relaxed);
                tracing::info!(target: "iroh::_events::qnt::init", "bounded probe");
            }
        });
        assert_eq!(events.load(Ordering::Relaxed), 2);
    }

    #[test]
    fn diagnostic_capture_is_opt_in_bounded_and_excludes_payload_spans() {
        let zero = Duration::ZERO;
        let target = "iroh::socket::remote_map::remote_state";
        assert!(!allowed(target, "event", true, false, zero));
        assert!(allowed(target, "event", true, true, zero));
        assert!(allowed(
            "iroh::_events::qnt::init",
            "event",
            true,
            true,
            zero
        ));
        assert!(allowed(
            "noq_proto::n0_nat_traversal",
            "event",
            true,
            true,
            zero
        ));
        assert!(allowed(target, "RemoteStateActor", false, true, zero));
        assert!(!allowed(target, "handle_message", false, true, zero));
        assert!(!allowed("iroh::socket", "event", true, true, zero));
        assert!(!allowed(
            "noq_proto::connection",
            "packet",
            true,
            true,
            zero
        ));
        assert!(!allowed(
            "iroh::_events::qnt::unreviewed",
            "event",
            true,
            true,
            zero
        ));
        assert!(allowed(
            target,
            "event",
            true,
            true,
            Duration::from_secs(299)
        ));
        assert!(!allowed(
            target,
            "event",
            true,
            true,
            Duration::from_secs(300)
        ));
        assert!(allowed(
            "data_fabric_transport",
            "event",
            true,
            false,
            Duration::from_secs(600)
        ));
    }
}
