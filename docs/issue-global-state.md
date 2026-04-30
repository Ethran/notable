# Code Quality Issue: Global Mutable State in DrawCanvas

## Description
In `DrawCanvas.kt`, there is a top-level, global mutable variable defined as:
```kotlin
var referencedSurfaceView: String = ""
```
This variable is then modified and used within `OnyxInputHandler.kt` to store a string representation of the handler's hashcode.
```kotlin
referencedSurfaceView = this.hashCode().toString()
```

Using top-level mutable global state (`var`) in a Kotlin project is a significant code smell. It breaks encapsulation, making it impossible to predict the state of the application when multiple instances of the class are created (for instance, if a user switches tabs or splits the screen). It also introduces hidden dependencies that complicate testing and concurrent execution, potentially causing race conditions.

## Recommended Fix
Eliminate the top-level variable entirely. Since it appears to act as an identifier or tracking mechanism for the surface view being used:
1. Define it as a private property within the instance of `OnyxInputHandler` or `DrawCanvas` itself.
2. If it is needed globally to coordinate a single Onyx SDK touch session, manage it within an injected singleton service or an `object` that specifically orchestrates the SDK.

Example fix in `OnyxInputHandler`:
```kotlin
private var referencedSurfaceView: String = ""
// ...
this.referencedSurfaceView = this.hashCode().toString()
```
