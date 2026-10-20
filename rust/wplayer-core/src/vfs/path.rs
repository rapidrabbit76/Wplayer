use crate::error::{CoreError, CoreResult};

/// Normalizes a source-relative path to `/a/b` form.
///
/// Rejects `..` components so a path can never escape the source root.
pub fn normalize(path: &str) -> CoreResult<String> {
    let mut parts = Vec::new();
    for part in path.split(['/', '\\']) {
        match part {
            "" | "." => {}
            ".." => {
                return Err(CoreError::InvalidArgument {
                    detail: format!("parent components are not allowed: {path}"),
                });
            }
            p => parts.push(p),
        }
    }
    Ok(format!("/{}", parts.join("/")))
}

pub fn join(dir: &str, name: &str) -> String {
    if dir.ends_with('/') {
        format!("{dir}{name}")
    } else {
        format!("{dir}/{name}")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_separators_and_dots() {
        assert_eq!(normalize("").unwrap(), "/");
        assert_eq!(normalize("a//b/./c/").unwrap(), "/a/b/c");
        assert_eq!(normalize("\\영화\\a.mkv").unwrap(), "/영화/a.mkv");
    }

    #[test]
    fn rejects_parent_components() {
        assert!(normalize("/a/../../etc").is_err());
    }

    #[test]
    fn joins_without_double_slash() {
        assert_eq!(join("/", "a"), "/a");
        assert_eq!(join("/a", "b"), "/a/b");
    }
}
