package com.ethran.notable.utils

/**
 * Lightweight functional result type.
 * Success carries data, Error carries a typed domain error.
 */
sealed interface AppResult<out D, out E : DomainError> {
    data class Success<out D>(val data: D) : AppResult<D, Nothing>
    data class Error<out E : DomainError>(val error: E) : AppResult<Nothing, E>
}

/**
 * Marker contract for typed domain errors handled by app layers.
 */
interface DomainErrorInterface {
    val userMessage: String
    val recoverable: Boolean
        get() = true

    val showErrorMessage: Boolean
        get() = true

    fun extendMessage(message: String): String {
        return if (message.isNotBlank())
            "$userMessage. $message"
        else
            userMessage
    }
}


sealed class DomainError(
    override val userMessage: String, override val recoverable: Boolean = true
) : DomainErrorInterface {

    data class NotFound(val resource: String) : DomainError("$resource was not found.")

    data class UnexpectedState(val message: String) : DomainError(message)

    data class NetworkError(val message: String) : DomainError(message)

    data class DatabaseError(val message: String) : DomainError(message)

    data class DrawingError(val message: String) : DomainError(message)

    // Sync Errors
    data object SyncAuthError : DomainError("Authentication failed. Please check your credentials.")
    data object SyncConfigError : DomainError("Sync is not configured correctly.")
    data class SyncClockSkew(val seconds: Long) :
        DomainError("Clock skew detected: ${seconds}s. Please check your device time.")

    data object SyncWifiRequired : DomainError("WiFi connection required for sync.")
    data object SyncInProgress : DomainError("Sync already in progress.")
    data object SyncConflict : DomainError("Conflict detected during sync.")

    data class SyncError(
        val message: String,
        override val recoverable: Boolean = true
    ) : DomainError(message, recoverable)


    data class MultipleErrors(val errors: List<DomainError>) : DomainError(
        userMessage = errors.joinToString(separator = "\n") { "• ${it.userMessage}" }
    )
}

/**
 * Extension to cleanly combine two DomainErrors using the `+` operator.
 * Usage: val combined = error1 + error2
 */
operator fun DomainError.plus(other: DomainError): DomainError.MultipleErrors {
    val leftList = if (this is DomainError.MultipleErrors) this.errors else listOf(this)
    val rightList = if (other is DomainError.MultipleErrors) other.errors else listOf(other)
    return DomainError.MultipleErrors(leftList + rightList)
}

inline fun <D, E : DomainError, R> AppResult<D, E>.fold(
    onSuccess: (D) -> R, onError: (E) -> R
): R = when (this) {
    is AppResult.Success -> onSuccess(data)
    is AppResult.Error -> onError(error)
}

inline fun <D, E : DomainError, T> AppResult<D, E>.map(
    transform: (D) -> T
): AppResult<T, E> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Error -> this
}

inline fun <D, E : DomainError, T> AppResult<D, E>.flatMap(
    transform: (D) -> AppResult<T, E>
): AppResult<T, E> = when (this) {
    is AppResult.Success -> transform(data)
    is AppResult.Error -> this
}

inline fun <D, E : DomainError, F : DomainError> AppResult<D, E>.mapError(
    transform: (E) -> F
): AppResult<D, F> = when (this) {
    is AppResult.Success -> this
    is AppResult.Error -> AppResult.Error(transform(error))
}

inline fun <D, E : DomainError> AppResult<D, E>.onSuccess(
    action: (D) -> Unit
): AppResult<D, E> {
    if (this is AppResult.Success) action(data)
    return this
}

inline fun <D, E : DomainError> AppResult<D, E>.onError(
    action: (E) -> Unit
): AppResult<D, E> {
    if (this is AppResult.Error) action(error)
    return this
}

inline fun <D, E : DomainError> AppResult<D, E>.getOrElse(defaultValue: (E) -> D): D = when (this) {
    is AppResult.Success -> data
    is AppResult.Error -> defaultValue(error)
}

fun <D, E : DomainError> AppResult<D, E>.getOrNull(): D? = when (this) {
    is AppResult.Success -> data
    is AppResult.Error -> null
}


/**
 * Returns data [D] on success, or executes [action] and interrupts the flow (Nothing) on error.
 */
inline fun <D, E : DomainError> AppResult<D, E>.onFailure(action: (E) -> Nothing): D {
    return when (this) {
        is AppResult.Success -> data
        is AppResult.Error -> action(error)
    }
}
