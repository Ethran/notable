package com.ethran.notable.utils

/**
 * Collects multiple non-fatal [DomainError]s during a loop and folds them into a single result.
 *
 * Replaces the repeated `persistentError = persistentError?.plus(error) ?: error` idiom (see
 * `docs/result-and-error-handling.md`). This is the same accumulation semantics — errors are
 * combined via [DomainError.plus] into a [DomainError.MultipleErrors] — just without the
 * hand-rolled nullable bookkeeping and the `!!` it forced at call sites.
 *
 * Not thread-safe; use one instance per loop.
 */
class ErrorAccumulator {
    private var error: DomainError? = null

    val hasErrors: Boolean get() = error != null

    /** Combine [e] into the accumulated error. */
    fun add(e: DomainError) {
        error = error?.plus(e) ?: e
    }

    /** If [result] is an error, accumulate it. Returns [result] unchanged for chaining. */
    fun <D, E : DomainError> addFrom(result: AppResult<D, E>): AppResult<D, E> {
        if (result is AppResult.Error) add(result.error)
        return result
    }

    /**
     * [AppResult.Error] with all accumulated errors, or [AppResult.Success] carrying [value]
     * when nothing was accumulated.
     */
    fun <D> asResult(value: D): AppResult<D, DomainError> =
        error?.let { AppResult.Error(it) } ?: AppResult.Success(value)
}
