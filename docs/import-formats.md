# Import formats

Short reference for contributors working with ImportEngine.

Maintainers
- Every new import format must live in its own file. Add your name and contact at the top of that file as a comment. You are responsible for maintaining that format going forward.

Overview
- Entrypoint: ImportEngine.import(uri, options)
- Options (ImportOptions):
    - saveToBookId: import into an existing book (TODO)
    - folderId: parent folder for a newly created notebook
    - conflictStrategy: planned merge strategies (TODO)
    - linkToExternalFile: link vs copy (currently used for PDF backgrounds)
    - fileType: optional MIME-type pre-check
    - bookTitle: optional notebook title override

Currently supported formats
- XOPP (Xournal++) — Maintainer: @Ethran
    - Detection: XoppFile.isXoppFile(mimeType, fileName)
    - Handler: handleImportXopp
    - Creates a notebook with defaultBackgroundType = Native (blank)
    - Parses pages, strokes, and images via XoppFile.importBook and stores them
- PDF — Maintainer: @Ethran
    - Detection: isPdfFile(mimeType, fileName)
    - Handler: handleImportPDF
    - Creates a notebook with defaultBackgroundType = AutoPdf and defaultBackground = source URI
    - importPdf(context, uri, options) yields pages; persisted like XOPP

### Minimal interface for new importers
- Put your parser in a separate file (e.g., PdfImporter.kt, MyFormatImporter.kt)
- Implement the following contract and wire it in ImportEngine.import:

```kotlin
fun importBook(uri: Uri, savePageToDatabase: (PageContent) -> Unit)
```

- PageContent is the standard unit:
```kotlin
data class PageContent(
    val page: Page,
    val strokes: List<Stroke>,
    val images: List<Image>
)
```

- The codebase is not perfect; if you know how to improve how imports are handled, feel free to open a PR

### Caveats
- saveToBookId and all conflict strategies are TODO
- Keep heavy I/O off the main thread (parsers should not block the UI)
- Be careful with large files and memory usage

Related files
- app/src/main/java/com/ethran/notable/io/ImportEngine.kt
- app/src/main/java/com/ethran/notable/io/XoppFile.kt