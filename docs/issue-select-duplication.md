# Code Quality Issue: Duplicated Path Logic in Select.kt

## Description
In `app/src/main/java/com/ethran/notable/editor/utils/Select.kt`, there is explicit code duplication between `selectStrokesFromPath` and `selectImagesFromPath` functions.

Both functions contain the exact same logic for computing the bounds of a path and shifting it into a 16-bit region format:
```kotlin
val bounds = RectF()
path.computeBounds(bounds, true)

//region is only 16 bit, so we need to move our region
val translatedPath = Path(path)
translatedPath.offset(0f, -bounds.top)
val region = pathToRegion(translatedPath)
```
This violates the DRY (Don't Repeat Yourself) principle. Any changes to how the selection boundaries are calculated or how the region offset is handled (e.g. for fixing the 16-bit clipping issue) would need to be made in two separate places.

## Recommended Fix
Extract the common bounds and region calculation logic into a reusable helper function or class.

```kotlin
data class SelectionRegion(val bounds: RectF, val region: Region)

fun createSelectionRegion(path: Path): SelectionRegion {
    val bounds = RectF()
    path.computeBounds(bounds, true)
    val translatedPath = Path(path)
    translatedPath.offset(0f, -bounds.top)
    return SelectionRegion(bounds, pathToRegion(translatedPath))
}
```
Then, update both `selectStrokesFromPath` and `selectImagesFromPath` to utilize this single source of truth for generating the selection boundary.
