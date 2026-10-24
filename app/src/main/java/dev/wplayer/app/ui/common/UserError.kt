package dev.wplayer.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.wplayer.app.R
import dev.wplayer.core.ffi.CoreException
import java.io.FileNotFoundException
import kotlin.coroutines.cancellation.CancellationException

/** A failure reduced to what the user can act on; rendered with [message]. */
data class UserError(val reason: Reason, val detail: String? = null) {
    enum class Reason {
        AUTHENTICATION,
        NOT_FOUND,
        PERMISSION,
        CONNECTION,
        UNSUPPORTED,

        /** An encrypted archive, a solid RAR4 one, or a page too large to take out. */
        UNSUPPORTED_ARCHIVE,
        OTHER,
    }

    companion object {
        fun from(error: Throwable): UserError = when (error) {
            is CoreException.AuthenticationFailed -> UserError(Reason.AUTHENTICATION)
            is CoreException.NotFound, is FileNotFoundException -> UserError(Reason.NOT_FOUND)
            is CoreException.PermissionDenied, is SecurityException -> UserError(Reason.PERMISSION)
            is CoreException.Connection -> UserError(Reason.CONNECTION, error.detail)
            is CoreException.Unsupported -> UserError(Reason.UNSUPPORTED, error.detail)
            else -> UserError(Reason.OTHER, error.message)
        }

        /** Like [from], for reading a comic archive or a page inside it. */
        fun fromArchive(error: Throwable): UserError =
            from(error).let { if (it.reason == Reason.UNSUPPORTED) it.copy(reason = Reason.UNSUPPORTED_ARCHIVE) else it }
    }
}

@Composable
fun UserError.message(): String {
    val text = when (reason) {
        UserError.Reason.AUTHENTICATION -> stringResource(R.string.error_authentication)
        UserError.Reason.NOT_FOUND -> stringResource(R.string.error_not_found)
        UserError.Reason.PERMISSION -> stringResource(R.string.error_permission)
        UserError.Reason.CONNECTION -> stringResource(R.string.error_connection)
        UserError.Reason.UNSUPPORTED -> stringResource(R.string.error_unsupported)
        UserError.Reason.UNSUPPORTED_ARCHIVE -> stringResource(R.string.error_unsupported_archive)
        UserError.Reason.OTHER -> stringResource(R.string.error_other)
    }
    return detail?.let { "$text ($it)" } ?: text
}

/**
 * Like [runCatching], but lets coroutine cancellation propagate. Use at UI
 * boundaries so no failure from the data layer or native core crashes the app.
 */
inline fun <T> catchingFailures(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
