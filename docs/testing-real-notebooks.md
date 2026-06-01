# Testing with "Real" Notebooks (DB Seeding)

This document describes how to prepare test data for instrumentation tests so that tests don't run on empty pages.

## Why is this important?

Crash:

```
java.lang.IllegalStateException: Unsupported concurrent change during composition
```

most often occurs during rapid page switching, when simultaneously:
- Compose performs composition / recomposition,
- and background code modifies objects based on `mutableStateOf`.

To catch such errors reliably, the test should run on a real data layout (pages + strokes + images), not a "blank page".

## Option A (Recommended): Deterministic Seeder (In-memory Room)

In instrumentation tests, you can create an in-memory database and seed it with data programmatically.

The repository includes a helper: `app/src/androidTest/java/com/ethran/notable/testing/TestNotebookSeeder.kt`.

Pros:
- 100% deterministic
- Fast
- No need to keep binary files in the repo

Cons:
- Data is not 1:1 what a user creates (but usually sufficient)

## Option B: Seed from a Real App Database ("Real Notebooks")

This option allows running tests on real notebooks created in the application.

### 1) Extract the database from the device/emulator

The easiest way is via **Device File Explorer** in Android Studio:

1. Run the app on the device.
2. Create a notebook with several pages and draw something on them.
3. Open `Device File Explorer`.
4. Navigate to:
   - `/data/data/com.ethran.notable/` (usually available on emulator),
   - then to the DB folder (Room): `.../databases/`.
5. Copy the database file named **`app_database`** (this is the name in `Db.kt`).

If Android Studio does not allow it (permission restrictions), use:

```bash
adb shell run-as com.ethran.notable ls -l databases
adb shell run-as com.ethran.notable cat databases/app_database > /sdcard/app_database
adb pull /sdcard/app_database ./app_database
```

### 2) Place the database as a test asset

Copy the file to:

```
app/src/androidTest/assets/seed/real_app_database
```

Note: the filename doesn't have to be `app_database` — just make sure you provide the correct asset path.

### 3) Run tests on DB from asset

Use the `TestDatabaseFactory.createFromAsset(...)` helper:

```kotlin
val db = TestDatabaseFactory.createFromAsset(
    context = context,
    assetPath = "seed/real_app_database"
)
```

Pros:
- Testing on real data

Cons:
- Binary file in the repo (large, variable)
- Must remember about the Room schema version (when DB version / migrations change)

## How to combine in CI?

Recommendation:
- CI: Use Option A (seeder) — fast and stable
- Local / for reproduction: Option B (real database) — great for reproducing edge cases
