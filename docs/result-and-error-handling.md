# AppResult & DomainError Architecture

Notable uses functional error handling to eliminate `try-catch` noise. Instead of throwing exceptions, functions return an `AppResult`.

---

## Core Components

### 1. [AppResult](../app/src/main/java/com/ethran/notable/utils/AppResult.kt)
A sealed interface representing the outcome of an operation:
* **`Success<D>`**: Contains result data.
* **`Error<E>`**: Contains a [DomainError](../app/src/main/java/com/ethran/notable/utils/AppResult.kt).

<details>
<summary>View Definition</summary>

```kotlin
sealed interface AppResult<out D, out E : DomainError> {
    data class Success<out D>(val data: D) : AppResult<D, Nothing>
    data class Error<out E : DomainError>(val error: E) : AppResult<Nothing, E>
}
```
</details>

### 2. [DomainError](../app/src/main/java/com/ethran/notable/utils/AppResult.kt)
Strongly-typed errors (a sealed class: `NotFound`, `DatabaseError`, `FileError`, `DrawingError`, the sync errors, …) with a mandatory `userMessage` and a `recoverable` flag (whether retrying can help; `RemoteMissing` is not recoverable).

**Error Accumulation:** You can combine multiple errors using the `+` operator. This is useful for loops where you don't want to "fail fast" on the first item (e.g., rendering multiple images).

<details>
<summary>View Accumulation Logic</summary>

```kotlin
// Combines two errors into MultipleErrors
operator fun DomainError.plus(other: DomainError): DomainError.MultipleErrors {
    val leftList = (this as? DomainError.MultipleErrors)?.errors ?: listOf(this)
    val rightList = (other as? DomainError.MultipleErrors)?.errors ?: listOf(other)
    return DomainError.MultipleErrors(leftList + rightList)
}
```
</details>

`MultipleErrors` is recoverable only if every error in it is.

### 3. [ErrorAccumulator](../app/src/main/java/com/ethran/notable/utils/ErrorAccumulator.kt)
Wraps the `+` idiom for loops: `add(error)` / `addFrom(result)` per item, then `asResult(value)` gives `Success(value)` if nothing failed, else `Error` with all collected errors. Not thread-safe; one instance per loop. Used by the sync services.

---

## Operations

| Type | Function | Usage                                                 |
| :--- | :--- |:------------------------------------------------------|
| **Chain** | `map`, `flatMap`, `mapError` | Transform or sequence operations, or convert the error. |
| **Side Effect** | `onSuccess`, `onError` | Log or trigger UI events without changing the result. |
| **Terminal** | `fold` | Use to handle both states.                            |
| **Unwrap** | `getOrNull`, `getOrElse` | Safely access data.                                   |
| **Early exit** | `onFailure` | Returns the data, or runs a block that must leave (`return`/`throw`) on error. |

---

## Project Example: Page Rendering

In [pageDrawing.kt](../app/src/main/java/com/ethran/notable/editor/drawing/pageDrawing.kt), `drawOnCanvasFromPage` iterates through images and accumulates any loading failures using the `+` operator.

<details>
<summary>View Usage Example</summary>

```kotlin
// Simplified extract from pageDrawing.kt
fun drawOnCanvasFromPage(...): AppResult<Unit, DomainError> {
    var persistentError: DomainError? = null

    page.images.forEach { image ->
        drawImage(...).onError { error ->
            // Accumulate multiple non-fatal errors
            persistentError = persistentError?.let { it + error } ?: error
        }
    }

    return persistentError?.let { AppResult.Error(it) } ?: AppResult.Success(Unit)
}
```
</details>

---

## Best Practices
1. **Never throw exceptions** for expected failures (network, missing files); return an `AppResult`.
2. **Use `+`** when processing batches of items to provide comprehensive feedback to the user.
