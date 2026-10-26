use std::fs::File;
use std::os::unix::fs::FileExt;
use std::path::PathBuf;
use std::time::UNIX_EPOCH;

use async_trait::async_trait;

use super::{Entry, RandomReader, Vfs, path, refuse_root};
use crate::error::{CoreError, CoreResult};

/// Device storage rooted at an absolute directory (e.g. `/storage/emulated/0`).
pub struct LocalVfs {
    root: PathBuf,
}

impl LocalVfs {
    pub fn new(root: &str) -> CoreResult<Self> {
        let root = PathBuf::from(root);
        if !root.is_absolute() {
            return Err(CoreError::InvalidArgument {
                detail: format!("local root must be absolute: {}", root.display()),
            });
        }
        Ok(Self { root })
    }

    fn resolve(&self, path: &str) -> CoreResult<(String, PathBuf)> {
        let normalized = path::normalize(path)?;
        let full = self.root.join(normalized.trim_start_matches('/'));
        Ok((normalized, full))
    }
}

#[async_trait]
impl Vfs for LocalVfs {
    async fn list(&self, path: &str) -> CoreResult<Vec<Entry>> {
        let (dir, full) = self.resolve(path)?;
        let mut reader = tokio::fs::read_dir(&full)
            .await
            .map_err(|e| CoreError::from_io(e, &dir))?;

        let mut entries = Vec::new();
        while let Some(item) = reader.next_entry().await? {
            let name = item.file_name().to_string_lossy().into_owned();
            // Follows symlinks so linked folders behave like folders.
            let Ok(meta) = tokio::fs::metadata(item.path()).await else {
                continue;
            };
            let modified_epoch_ms = meta
                .modified()
                .ok()
                .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                .map(|d| d.as_millis() as i64);
            entries.push(Entry {
                path: path::join(&dir, &name),
                name,
                is_dir: meta.is_dir(),
                size: if meta.is_dir() { 0 } else { meta.len() },
                modified_epoch_ms,
            });
        }
        Ok(entries)
    }

    fn open(&self, path: &str) -> CoreResult<Box<dyn RandomReader>> {
        let (normalized, full) = self.resolve(path)?;
        let file = File::open(&full).map_err(|e| CoreError::from_io(e, &normalized))?;
        let size = file.metadata()?.len();
        Ok(Box::new(LocalReader { file, size }))
    }

    async fn delete(&self, path: &str) -> CoreResult<()> {
        let (normalized, full) = self.resolve(path)?;
        tokio::fs::remove_file(&full)
            .await
            .map_err(|e| CoreError::from_io(e, &normalized))
    }

    async fn delete_folder(&self, path: &str) -> CoreResult<()> {
        let (normalized, full) = self.resolve(path)?;
        refuse_root(&normalized)?;
        // Removes a linked folder's link, not what it points to.
        tokio::fs::remove_dir_all(&full)
            .await
            .map_err(|e| CoreError::from_io(e, &normalized))
    }
}

struct LocalReader {
    file: File,
    size: u64,
}

impl RandomReader for LocalReader {
    fn size(&self) -> Option<u64> {
        Some(self.size)
    }

    fn read_at(&mut self, offset: u64, buf: &mut [u8]) -> CoreResult<usize> {
        Ok(self.file.read_at(buf, offset)?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn lists_and_reads_files() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir(dir.path().join("영화")).unwrap();
        std::fs::write(dir.path().join("영화/a.mkv"), b"0123456789").unwrap();

        let vfs = LocalVfs::new(dir.path().to_str().unwrap()).unwrap();
        let root = vfs.list("/").await.unwrap();
        assert_eq!(root.len(), 1);
        assert!(root[0].is_dir);
        assert_eq!(root[0].path, "/영화");

        let files = vfs.list("/영화").await.unwrap();
        assert_eq!(files[0].size, 10);

        let mut reader = vfs.open(&files[0].path).unwrap();
        let mut buf = [0u8; 4];
        assert_eq!(reader.read_at(6, &mut buf).unwrap(), 4);
        assert_eq!(&buf, b"6789");
        assert_eq!(reader.read_at(10, &mut buf).unwrap(), 0);
    }

    #[tokio::test]
    async fn deletes_files_but_not_folders() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir(dir.path().join("영화")).unwrap();
        std::fs::write(dir.path().join("영화/a.mkv"), b"0").unwrap();

        let vfs = LocalVfs::new(dir.path().to_str().unwrap()).unwrap();
        vfs.delete("/영화/a.mkv").await.unwrap();
        assert!(vfs.list("/영화").await.unwrap().is_empty());
        assert!(matches!(
            vfs.delete("/영화/a.mkv").await,
            Err(CoreError::NotFound { .. })
        ));
        assert!(vfs.delete("/영화").await.is_err());
        assert!(dir.path().join("영화").is_dir());
    }

    #[tokio::test]
    async fn deletes_folders_with_everything_inside() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("영화/Season 1/자막")).unwrap();
        std::fs::write(dir.path().join("영화/Season 1/e01.mkv"), b"0").unwrap();
        std::fs::write(dir.path().join("영화/Season 1/자막/e01.srt"), b"0").unwrap();
        std::fs::write(dir.path().join("영화/a.mkv"), b"0").unwrap();

        let vfs = LocalVfs::new(dir.path().to_str().unwrap()).unwrap();
        assert!(vfs.delete_folder("/영화/a.mkv").await.is_err());
        assert!(vfs.delete_folder("/").await.is_err());
        vfs.delete_folder("/영화/Season 1").await.unwrap();
        let left = vfs.list("/영화").await.unwrap();
        assert_eq!(left.iter().map(|e| e.name.as_str()).collect::<Vec<_>>(), ["a.mkv"]);
        assert!(matches!(
            vfs.delete_folder("/영화/Season 1").await,
            Err(CoreError::NotFound { .. })
        ));
    }

    #[test]
    fn refuses_to_escape_root() {
        let dir = tempfile::tempdir().unwrap();
        let vfs = LocalVfs::new(dir.path().to_str().unwrap()).unwrap();
        assert!(vfs.open("/../secret").is_err());
    }
}
