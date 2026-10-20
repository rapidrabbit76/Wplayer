//! The single tokio runtime shared by all network I/O.
//!
//! UniFFI async exports spawn onto this runtime and await the `JoinHandle`,
//! which can be polled by the Kotlin coroutine executor.

use std::future::Future;
use std::sync::LazyLock;

use tokio::runtime::Runtime;
use tokio::task::JoinHandle;

static RUNTIME: LazyLock<Runtime> = LazyLock::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(4)
        .thread_name("wplayer-io")
        .enable_all()
        .build()
        .expect("failed to build tokio runtime")
});

pub(crate) fn spawn<F>(future: F) -> JoinHandle<F::Output>
where
    F: Future + Send + 'static,
    F::Output: Send + 'static,
{
    RUNTIME.spawn(future)
}

/// Runs a future to completion from a non-runtime thread (e.g. a libmpv stream callback).
///
/// # Panics
/// When called from a runtime worker thread.
pub(crate) fn block_on<F: Future>(future: F) -> F::Output {
    RUNTIME.block_on(future)
}

/// Makes tokio APIs usable on the current (non-runtime) thread until dropped,
/// e.g. inside libmpv callbacks whose drops may spawn cleanup tasks.
pub(crate) fn enter() -> tokio::runtime::EnterGuard<'static> {
    RUNTIME.enter()
}
