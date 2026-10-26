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

/// Deletes one file of a registered source. Folders are refused.
#[uniffi::export]
pub async fn delete_file(source_id: String, path: String) -> CoreResult<()> {
    let vfs = source::get(&source_id)?;
    runtime::spawn(async move { vfs.delete(&path).await }).await?
}

/// Deletes a folder of a registered source with everything in it. Files and the source root are refused.
#[uniffi::export]
pub async fn delete_folder(source_id: String, path: String) -> CoreResult<()> {
    let vfs = source::get(&source_id)?;
    runtime::spawn(async move { vfs.delete_folder(&path).await }).await?
}
