# Bug: OnyxInputHandler Crash due to Missing Pen Settings

## Description
In `OnyxInputHandler.kt`, when trying to access stroke size and color from `toolbarState.penSettings`, the code relies on a non-null assertion (`!!`). Specifically, expressions like `toolbarState.penSettings[toolbarState.pen.penName]!!.strokeSize` assume that the currently selected pen is always present in the `penSettings` map. As noted in `EditorViewModel.kt` (`// TODO: if it is an emptyMap(), the DrawCanvas crashes, to be fixed.`), if the map is empty or the specific pen configuration is missing, this results in a `NullPointerException`, crashing the application.

## How to Reproduce
1. Run the app on an Onyx device (or modify `DeviceCompat.isOnyxDevice` to true to instantiate the touch helper).
2. Set the `penSettings` state in `EditorViewModel` to `emptyMap()` (or an incomplete map).
3. Attempt to draw on the canvas using the stylus to trigger `onRawDrawingList` in `OnyxInputHandler`.
4. The application crashes with a `NullPointerException`.

## Recommended Fix
Remove the non-null assertions (`!!`) and use a safe call (`?.`) with a fallback default setting, or ensure `penSettings` is guaranteed to contain a default via a robust fallback mechanism.

For example, replace:
```kotlin
toolbarState.penSettings[toolbarState.pen.penName]!!.strokeSize
```
With:
```kotlin
val currentPenSetting = toolbarState.penSettings[toolbarState.pen.penName] 
    ?: DEFAULT_PEN_SETTINGS[toolbarState.pen.penName] 
    ?: PenSetting(strokeSize = 5f, color = Color.BLACK)
currentPenSetting.strokeSize
```
