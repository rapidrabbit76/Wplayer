use std::io;

/// Errors surfaced to Kotlin as `CoreException` subclasses.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("not found: {path}")]
    NotFound { path: String },
    #[error("permission denied: {path}")]
    PermissionDenied { path: String },
    #[error("authentication failed")]
    AuthenticationFailed,
    #[error("connection failed: {detail}")]
    Connection { detail: String },
    #[error("invalid argument: {detail}")]
    InvalidArgument { detail: String },
    #[error("unsupported: {detail}")]
    Unsupported { detail: String },
    #[error("player error: {detail}")]
    Player { detail: String },
    #[error("i/o error: {detail}")]
    Io { detail: String },
    #[error("link plugin failed: {detail}")]
    Plugin { detail: String },
    #[error("cancelled")]
    Cancelled,
}

impl CoreError {
    pub(crate) fn from_io(err: io::Error, path: &str) -> Self {
        match err.kind() {
            io::ErrorKind::NotFound => Self::NotFound { path: path.to_owned() },
            io::ErrorKind::PermissionDenied => Self::PermissionDenied { path: path.to_owned() },
            _ => Self::Io {
                detail: err.to_string(),
            },
        }
    }
}

impl From<io::Error> for CoreError {
    fn from(err: io::Error) -> Self {
        Self::Io {
            detail: err.to_string(),
        }
    }
}

impl From<tokio::task::JoinError> for CoreError {
    fn from(err: tokio::task::JoinError) -> Self {
        if err.is_cancelled() {
            Self::Cancelled
        } else {
            Self::Io {
                detail: err.to_string(),
            }
        }
    }
}

pub type CoreResult<T> = Result<T, CoreError>;
