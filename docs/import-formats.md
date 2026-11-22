# Import formats

Short reference for contributors working with ImportEngine.

Maintainers
- Every new import format must live in its own file. Add your name and contact at the top of that file as a comment. You are responsible for maintaining that format going forward.

Overview
- Entrypoint: `ImportEngine.import(uri, options)` — a suspend `@WorkerThread` function returning
  `AppResult<List<String>, DomainError>` (the ids of the imported pages, or the error).
- Callers: the library's import buttons (`LibraryViewModel.onXoppFile` / `onPdfFile`) and
  `notable://` PDF links (`DeepLinkResolver.import`, which always links the file).
- Options (ImportOptions):
    - saveToBookId: import into an existing book (TODO; `import` currently fails with `TODO()`)
    - folderId: parent folder for a newly created notebook
    - conflictStrategy: planned merge strategies (ImportConflictStrategy, TODO)
    - linkToExternalFile: link vs copy (used for PDFs)
    - fileType: optional MIME-type pre-check
    - bookTitle: optional notebook title override
- The file type is decided from the URI's MIME type or the file name's extension: either one
  matching is enough, so `book.pdf` reported as `text/plain` still imports as a PDF. The title
  override `bookTitle` is never used for this.
- XOPP is checked first, and it also accepts the generic MIME types `application/gzip` and
  `application/octet-stream`. So `book.pdf` reported as `application/octet-stream` goes to the XOPP
  importer, not the PDF one, and fails.
- Notebook title: `bookTitle` or the file name, with a `.xopp` / `.pdf` extension removed
  (any case), then sanitized: only letters, digits, spaces and dots kept, whitespace and dots
  collapsed, trimmed, at most 100 characters.

Currently supported formats
- XOPP (Xournal++) — Maintainer: @Ethran
    - Detection: XoppFile.isXoppFile(mimeType, fileName) — MIME `application/x-xopp`,
      `application/gzip` or `application/octet-stream`, or a `.xopp` name
    - Handler: handleImportXopp
    - Creates a notebook with defaultBackgroundType = Native (blank)
    - XoppFile.importBook streams the gzipped XML with a pull parser: each `<page>` becomes a Page,
      strokes are saved in batches of 500, images (decoded into `notabledb/images`) are saved when
      the page ends. Coordinates and widths are scaled from Xournal++ A4 units to Notable's page
      width; per-point pressure (from the `width` attribute) is stored normalized to [0, 1].
      Page backgrounds and page sizes from the file are not imported.
- PDF — Maintainer: @Ethran
    - Detection: isPdfFile(mimeType, fileName) — MIME `application/pdf` or a `.pdf` name
    - Handler: handleImportPDF
    - `handleFileSaving` either copies the PDF into `notabledb/backgrounds/pdfs` (copy) or resolves the
      original file's path (link); a PDF with no readable pages is rejected before any notebook
      is created
    - Creates a notebook with defaultBackgroundType = AutoPdf and defaultBackground = that file path
    - importPdf creates one empty page per PDF page: backgroundType `pdf<N>` when copied,
      `autoPdf` when linked (so the notebook follows changes to the file)

### Minimal interface for new importers
- Put your parser in a separate file (e.g., PdfImporter.kt, MyFormatImporter.kt)
- Detect the format in ImportEngine.import and add a `handleImport…` function that creates the
  notebook and persists what the parser produces through the repositories.
- Prefer a streaming contract like XoppFile's, so a large file is never held in memory at once:

```kotlin
suspend fun importBook(
    uri: Uri,
    onPageCreated: suspend (Page) -> Unit,
    onStrokeBatch: suspend (List<Stroke>) -> Unit,
    onPageFinalized: suspend (pageId: String, images: List<Image>) -> Unit,
)
```

- For simple formats a whole page at a time is fine, as `importPdf` does with
  `savePageToDatabase: suspend (PageWithData) -> Unit` (`PageWithData` = page + strokes + images).

- The codebase is not perfect; if you know how to improve how imports are handled, feel free to open a PR

### Caveats
- saveToBookId and all conflict strategies are TODO
- Keep heavy I/O off the main thread (parsers should not block the UI)
- Be careful with large files and memory usage

Related files
- app/src/main/java/com/ethran/notable/io/ImportEngine.kt
- app/src/main/java/com/ethran/notable/io/XoppFile.kt
- app/src/main/java/com/ethran/notable/io/importPdf.kt