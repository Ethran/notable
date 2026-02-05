# Export formats

Short reference for contributors working with ExportEngine.

Maintainers
- Every new export format must live in its own helper file. Add your name and contact at the top of that file as a comment. You are responsible for maintaining that format going forward.

Overview
- Entrypoint: ExportEngine.export(target, format, options)
- Targets:
    - Book(bookId): all pages of a notebook
    - Page(pageId): a single page
- Options (ExportOptions):
    - targetFolderUri: destination directory (SAF or file://)
    - overwrite: best-effort replacement of existing files (currently **not working** - replaces all the files.)
    - fileName: base name override (extension added automatically)

Currently supported formats
Core (Maintainer: @Ethran)
- PDF
    - Book: multi-page; pagination via GlobalAppSettings.current.paginatePdf
    - Page: single page (or paginated)
    - Scales to A4 width; splits by A4 height if enabled
- PNG
    - Book: <file>-p1.png, <file>-p2.png, ...
    - Page: single PNG
- JPEG
    - Same as PNG
- XOPP (Xournal++)
    - Book/Page: gzipped XML via XoppFile.writeToXoppStream
    - Includes tools, colors, pressure-derived widths; images embedded as base64

### Minimal interface for new exporters
- Keep in mind that the export logic should evolve so ExportEngine provides the data and the format handler does the rest (similar to imports). See TODO in ExportEngine.
- Put your exporter logic in a separate helper file (e.g., MyFormatExport.kt).
- Implement a stream writer similar to XoppFile:

```kotlin
// Write the entire file into the provided OutputStream.
// Do not close 'output'; the caller manages it.
fun writeToMyFormatStream(target: ExportTarget, output: OutputStream)
```

- Wire it in ExportEngine.export:
    - Add an enum value to ExportFormat
    - In export(), dispatch to a handler that calls:
```kotlin
// Inside ExportEngine
saveStream(
    folderUri = folderUri,
    fileName = baseFileName,
    extension = "<ext>",
    mimeType = "<mime>",
    overwrite = options.overwrite
) { out -> MyFormatExport(context).writeToMyFormatStream(target, out) }
```

- For per-page image-like formats, mirror exportAsImages behavior (file naming: <base>-pN.<ext>)
- The codebase is not perfect; if you know how to improve how exports are handled, feel free to open a PR

### Default file naming and folders
- Default location: Documents/notable/<subfolder>
    - Subfolder (createSubfolderName):
        - Book (PDF/XOPP): book hierarchy (no extra book folder)
        - Book (PNG/JPEG): hierarchy + book title folder
        - Page in a book: hierarchy + book title folder
        - Quick page: page’s hierarchy
- File name (createFileName):
    - Book: BookTitle
    - Page in a book: BookTitle-p<PageNumber> (or “p_” if unknown)
    - Quick page: quickpage-<timestamp>

### Caveats
- Rendering and pagination depend on GlobalAppSettings
- Avoid main-thread work
- Not all URIs are tested; there might be errors

Related files
- app/src/main/java/com/ethran/notable/io/ExportEngine.kt
- app/src/main/java/com/ethran/notable/io/XoppFile.kt