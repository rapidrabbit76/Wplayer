use crate::error::CoreResult;
use crate::runtime;
use crate::source;
use crate::vfs::Entry;

/// Lists one directory of a registered source. Order is unspecified.
#[uniffi::export]
pub async fn list_directory(source_id: String, path: String) -> CoreResult<Vec<Entry>> {
    let vfs = source::get(&source_id)?;
    runtime::spawn(async move { vfs.list(&path).await }).await?
}
