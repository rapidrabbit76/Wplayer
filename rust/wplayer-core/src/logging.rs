use std::sync::Once;

static INIT: Once = Once::new();

pub(crate) fn init() {
    INIT.call_once(|| {
        #[cfg(target_os = "android")]
        android_logger::init_once(
            android_logger::Config::default()
                .with_tag("wplayer-core")
                .with_max_level(log::LevelFilter::Info)
                // The SMB client logs every message at debug level; that costs CPU on every read.
                .with_filter(
                    android_logger::FilterBuilder::new()
                        .filter_level(log::LevelFilter::Info)
                        .filter_module("smb", log::LevelFilter::Warn)
                        .filter_module("smb_transport", log::LevelFilter::Warn)
                        .build(),
                ),
        );
    });
}
