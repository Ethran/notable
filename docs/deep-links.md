# Deep Links and PKM Integration

Notable exposes a deep link API and JSON index for integration with external tools such as Emacs, Obsidian, and other personal knowledge management (PKM) systems.

## Deep Link Scheme

All deep links use the `notable://` scheme. On Android, these links are handled by the app's intent filter.

### Navigation

| Link | Description |
|------|-------------|
| `notable://page-{id}` | Open page by UUID |
| `notable://book-{id}` | Open notebook by UUID |

### Creation (Boox only)

These links create new items. Creation requires the Onyx Boox system API and is unavailable on other Android devices.

| Link | Description |
|------|-------------|
| `notable://new-folder?name={name}&parent={parentPath}` | Create folder |
| `notable://new-book?name={name}&folder={folderPath}` | Create notebook |
| `notable://new-page/{uuid}?name={name}&folder={folderPath}` | Create quick page with specified UUID |
| `notable://book/{bookId}/new-page/{uuid}?name={name}` | Create page in notebook with specified UUID |

Parameters:
- `name` (optional): Display name for the item
- `parent` / `folder` (optional): Folder path (e.g., "Work/Projects")
- `uuid`: Client-generated UUID for the new page (allows immediate link creation)

### Export

| Link | Description |
|------|-------------|
| `notable://export/page/{id}?format={format}` | Export page |
| `notable://export/book/{id}?format={format}` | Export notebook |

Supported formats: `pdf`, `png`, `jpg`, `xopp`

### Utility

| Link | Description |
|------|-------------|
| `notable://sync-index` | Force index regeneration |

## JSON Index

Notable exports a lightweight JSON index for external tools to browse the notebook structure without accessing the database directly.

### Location

```
Documents/notabledb/notable-index.json
```

### Structure

```json
{
  "version": 2,
  "exportFormats": ["pdf", "png", "jpg", "xopp"],
  "folders": [
    {
      "id": "uuid",
      "name": "Folder Name",
      "parentId": "parent-uuid or null",
      "path": "Parent/Folder Name"
    }
  ],
  "notebooks": [
    {
      "id": "uuid",
      "name": "Notebook Title",
      "folderId": "folder-uuid or null",
      "folderPath": "Folder/Path or null",
      "pageIds": ["page-uuid-1", "page-uuid-2"],
      "pageCount": 2
    }
  ],
  "pages": [
    {
      "id": "uuid",
      "name": "Page Name or null",
      "notebookId": "notebook-uuid or null",
      "folderId": "folder-uuid or null",
      "folderPath": "Folder/Path or null",
      "pageIndex": 0
    }
  ]
}
```

### Index Updates

The index is regenerated:
- On app startup
- When the app goes to background
- On `notable://sync-index` deep link

Updates are debounced (2-second delay) to avoid excessive writes during rapid changes.

## Export Directory Structure

Exported files are stored under `Documents/notable/` with the following structure:

```
Documents/notable/
  FolderPath/
    BookTitle.pdf           # Book export (PDF/XOPP)
    BookTitle/
      BookTitle-p1.png      # Book pages (PNG/JPG)
      BookTitle-p2.png
    BookTitle-p1.pdf        # Individual page export
  quickpage-2025-01-31_14-30.pdf  # Quick page export
```

## Emacs Integration

An Emacs package (`notable.el`) provides a transient-based interface for Notable integration.

### Installation

1. Copy `notable.el` to your Emacs load path
2. Add to your config:
   ```elisp
   (require 'notable)
   ```

### Configuration

```elisp
(defcustom notable-index-file
  ;; Android: Documents/notabledb/notable-index.json
  ;; Desktop: ~/Notes/notabledb/notable-index.json
  )

(defcustom notable-export-directory
  ;; Android: Documents/notable
  ;; Desktop: ~/Notes/notable
  )
```

### Device Detection

The package uses device detection variables to show appropriate features:

| Variable | Description |
|----------|-------------|
| `IS-ANDROID` | Running on Android (Termux/Emacs Android port) |
| `IS-ONYX` | Running on Onyx Boox e-reader |

Define these in your config before loading `notable.el`, or the package will use fallback detection.

### Commands

Invoke `M-x notable` to open the transient menu.

**Navigation (Android only)**
- `o p` - Open page in Notable
- `o b` - Open notebook in Notable

**Create (Boox only)**
- `c f` - Create folder
- `c b` - Create notebook
- `c p` - Create quick page (inserts org-mode link at point)
- `c P` - Create page in notebook

**Export (Android only)**
- `e p` - Export page
- `e b` - Export notebook

**View Exports (all devices)**
- `v p` - View page export (PDF, PNG, etc.)
- `v b` - View notebook export

**Links (all devices)**
- `l p` - Insert page link
- `l b` - Insert notebook link

**Utility (Android only)**
- `r` - Refresh index

### Org-Mode Links

The package registers `notable://` as an org-mode link type. Clicking a Notable link on Android opens the page/notebook in the app.

```org
[[notable://page-abc123][My Page]]
[[notable://book-def456][My Notebook]]
```

## Implementation Files

| File | Purpose |
|------|---------|
| `io/IndexExporter.kt` | JSON index generation |
| `ui/Router.kt` | Deep link routing and handlers |
| `data/db/Page.kt` | Page entity with `name` field |
| `data/db/Folder.kt` | Folder DAO with `getAll()`, `getByTitle()` |
| `data/db/Notebook.kt` | Notebook DAO with `getAll()` |
| `MainActivity.kt` | Index export on startup/pause |

## Removed Features

This implementation replaces the previous clipboard-based link copying system. The following settings have been removed from `AppSettings`:
- `linkCopyEnabled`
- `linkTemplate`
- `exportBaseDirectory`

The JSON decoder uses `ignoreUnknownKeys = true` for backward compatibility with existing settings.
