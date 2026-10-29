<!-- markdownlint-configure-file {
  "MD013": {"code_blocks": false, "tables": false},
  "MD033": false,
  "MD041": false
} -->

<div align="center">

[![License][license-shield]][license-url]
[![Total Downloads][downloads-shield]][downloads-url]
[![Discord][discord-shield]][discord-url]

![Notable App][logo]

# Notable

Handwritten notes for Onyx BOOX e-ink tablets. Continues the archived [olup/notable](https://github.com/olup/notable).

[![🐛 Report Bug][bug-shield]][bug-url]
[![Download Latest][download-shield]][download-url]
[![💡 Request Feature][feature-shield]][feature-url]

<a href="https://github.com/sponsors/ethran">
  <img src="https://img.shields.io/badge/Sponsor_on-GitHub-%23ea4aaa?logo=githubsponsors&style=for-the-badge" alt="Sponsor on GitHub">
</a>

<a href="https://ko-fi.com/rethran" target="_blank">
  <img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="Support me on Ko-fi">
</a>

</div>

---
<details>
  <summary>Table of Contents</summary>

- [About](#about)
- [Features](#features)
- [Download](#download)
- [Gestures](#gestures)
- [System Requirements and Permissions](#system-requirements-and-permissions)
- [Export and Import](#export-and-import)
- [Roadmap](#roadmap)
- [Troubleshooting and FAQ](#troubleshooting-and-faq)
- [Bug Reporting](#bug-reporting)
- [Screenshots](#screenshots)
- [Working with LaTeX](#working-with-latex)
- [Links](#links)
- [App Distribution](#app-distribution)
- [For Developers & Contributing](#for-developers--contributing)

</details>


---

## About
This project began as a fork of the original Notable app and has since evolved into a continuation of it. The architecture is largely the same, but many of the functions have been rewritten and expanded with a focus on practical, everyday use. Development is active when possible, guided by the principle that the app must be fast and dependable — performance comes first, and the basics need to feel right before new features are introduced. Waiting for things to load is seen as unacceptable, so responsiveness is a core priority.

Future plans include exploring how AI can enhance the app, with a focus on solutions that run directly on the device. A long-term goal is local handwriting conversion to LaTeX or plain text, making advanced features available without relying on external services.

---

## Features
* ⚡ **Fast page turns with caching:** smooth, swift page transitions, including quick navigation to the next and previous pages.
* ↕️ **Infinite vertical scroll:** a virtually endless canvas for notes with smooth vertical scrolling.
* 📝 **Quick Pages:** instantly create a new page.
* 📒 **Notebooks:** group related notes and switch easily between notebooks.
* 📁 **Folders:** organize notes with folders.
* 🤏 **Configurable gestures:** [gesture controls](#gestures) in the editor, adjustable in Settings.
* 🧰 **Customizable toolbar:** choose and reorder tools, and create custom pens.
* 🌅 **Images:** add, move, scale, and remove images.
* ➤ **Selection export:** export or share selected handwriting as PNG.
* ✏️ **Scribble to erase:** erase content by scribbling over it (disabled by default) — contributed by [@niknal357](https://github.com/niknal357).
* 🔄 **Auto-refresh on background change:** useful when using a tablet as a second display — see [Working with LaTeX](#working-with-latex).
* ☁️ **WebDAV sync (experimental):** sync notebooks with a WebDAV server. Still in testing — keep backups.

---

## Download
**Download the latest stable version of the [Notable app here.](https://github.com/Ethran/notable/releases/latest)**

Alternatively, get the latest build from the main branch via the ["next" release](https://github.com/Ethran/notable/releases/next).

Open the **Assets** section of the release and select the `.apk` file.

<details><summary title="Click to show/hide details">❓ Where can I see alternative/older releases?</summary><br/>
See <a href="https://github.com/Ethran/notable/releases" target="_blank">all releases</a>, or the <a href="https://github.com/olup/notable/tags" target="_blank">original olup releases</a>.
</details>

<details><summary title="Click to show/hide details">❓ What is a 'next' release?</summary><br/>
The "next" release is a pre-release and may contain features implemented but not yet released as part of a stable version — and sometimes experiments that may not make it into a release.
</details>

---

## Gestures
Default gestures in the editor. Most can be changed in **Settings → Gestures**.

#### ☝️ 1 Finger
* **Swipe up or down:** scroll the page.
* **Swipe left or right:** change to the previous/next page (only available in notebooks).
* **Double tap:** undo.
* **Hold and drag:** select text and images.

#### ✌️ 2 Fingers
* **Single tap:** switch between writing and eraser modes.
* **Pinch:** zoom in and out.
* **Drag:** move the canvas.

#### 🤟 3 Fingers
* **Swipe left or right:** show or hide the toolbar.
* **Swipe up:** open QuickNav (can be disabled in Gesture settings).

> ⚠️ **Caveat:** on Onyx devices, a three-finger swipe is also the system's screenshot gesture, which can steal the touch before Notable sees it. Enabling **"Block system gestures while in Notable"** in Gesture settings fixes this, but as a side effect, edge-navigation swipes (e.g. back/recent-apps) stop working while the app is open.

#### 🔲 Selection
* **Drag:** move the selection.
* **Double tap:** copy the selected writing.

---

## System Requirements and Permissions
The app targets Onyx BOOX devices and requires Android 10 (SDK 29) or higher. Limited support for Android 9 (SDK 28) may be possible if [issue #93](https://github.com/Ethran/notable/issues/93) is resolved. Handwriting functionality is currently not available on non-Onyx devices. Enabling handwriting on other devices may be possible in the future but is not supported at the moment.

Storage access is required to manage notes, assets, and to observe PDF backgrounds, which need “all files access”. The database is stored at `Documents/notabledb` to simplify backups and reduce the risk of accidental deletion. Exports are written to `Documents/notable`.

---

## Export and Import

The app supports the following formats:

- **PDF** — export and import supported. You can also link a page to an external PDF so that changes on your computer are reflected live on the tablet (see [Working with LaTeX](#working-with-latex)).  
- **PNG** — export supported for handwriting selections, individual pages, and entire books.  
- **JPEG** — export supported for individual pages.  
- **XOPP** — export and import partially supported. Only stroke and image data are preserved; tool information for strokes may be lost when files are opened and saved with [Xournal++](https://xournalpp.github.io/). Backgrounds are not exported.  


---

## Roadmap
This is a rough, unordered list of ideas and in-progress work, not a committed schedule — priorities shift depending on what's needed at the time.

- Currently being worked on:
  - Non-Onyx device support: make Notable usable on other Android devices, without depending on the Onyx SDK.
- Sync: stabilize WebDAV sync.
- Better selection tools:
  - Stroke editing (color, size, etc.)
  - Rotate and flip selection
  - Auto‑scroll when dragging a selection near screen edges
  - Easier selection movement, including dragging while scrolling
- PDF improvements:
  - Allow saving annotations back to the original PDF
  - Improved rendering and stability across devices
- PDF annotation enhancements:
  - Display annotations from other programs
  - Additional quality‑of‑life tools for annotating imported PDFs
- Bookmarks, tags, and internal links — see [issue #52](https://github.com/Ethran/notable/issues/52), including link export to PDF.
- Figure and text recognition — see [issue #44](https://github.com/Ethran/notable/issues/44):
  - Searchable notes
  - Automatic creation of tag descriptions
  - Shape recognition
  - Handwriting to Latex

---

## Troubleshooting and FAQ
**What are “NeoTools,” and why are some disabled?**
NeoTools are components of the Onyx E-Ink toolset, made available through Onyx’s libraries. However, certain tools are unstable and can cause crashes, so they are disabled by default to ensure better app stability. Examples include:

* `com.onyx.android.sdk.pen.NeoCharcoalPenV2`
* `com.onyx.android.sdk.pen.NeoMarkerPen`
* `com.onyx.android.sdk.pen.NeoBrushPen`

---

## Bug Reporting

If you encounter unexpected behavior, please include an app log with your report. To do this:  
1. Navigate to the page where the issue occurs.  
2. Reproduce the problem.  
3. Open the page menu.  
4. Select **“Bug Report”** and either copy the log or submit it directly.  

This will open a new GitHub issue in your browser with useful device information attached, which greatly helps in diagnosing and resolving the problem.  

Bug reporting with logs is currently supported only in notebooks/pages. Issues outside of writing are unlikely to require this level of detail.  

If the app crashes, attach the log from **Settings → Debug → Crash logs**.

---


## Screenshots

<div style="display: flex; flex-wrap: wrap; gap: 10px;">
  <img src="https://github.com/user-attachments/assets/c3054254-043b-4cce-8524-43d10505ad0b" alt="Writing on a page" width="200"/>
  <img src="https://github.com/user-attachments/assets/c23119b7-cdae-4742-83f2-a4f39863c571" alt="Notebook overview" width="200"/>
  <img src="https://github.com/user-attachments/assets/9f3e7012-69e4-4125-bf69-509b52e1ebaf" alt="Gestures and selection" width="200"/>
  <img src="https://github.com/user-attachments/assets/24c8c750-eb8e-4f01-ac62-6a9f8e5f9e4f" alt="Image handling" width="200"/>
  <img src="https://github.com/user-attachments/assets/4cdb0e74-bfce-4dba-bc21-886a5834401e" alt="Toolbar and tools" width="200"/>
  <img src="https://github.com/user-attachments/assets/f37ec6c9-fda3-41d1-8933-940c2806c6b0" alt="Page management" width="200"/>
  <img src="https://github.com/user-attachments/assets/e8304495-dbab-4d7a-987a-b76bf91a3a74" alt="PDF viewing" width="200"/>
  <img src="https://github.com/user-attachments/assets/38226966-0e19-45c9-a318-a8fd9d8edf02" alt="Customization" width="200"/>
  <img src="https://github.com/user-attachments/assets/df29f77c-94a8-4c56-bbd4-d7285654df30" alt="Settings" width="200"/>
</div>

---

## Working with LaTeX

The app can be used as a **primitive second monitor** for LaTeX editing — previewing compiled PDFs
in real time on your tablet.

### Steps:

- Connect your device to your computer via USB (MTP).
- Set up automatic copying of the compiled PDF to the tablet:
  <details>
  <summary>Example using a custom <code>latexmkrc</code>:</summary>

  ```perl
  $pdf_mode = 1;
  $out_dir = 'build';

  sub postprocess {
      system("cp build/main.pdf '/run/user/1000/gvfs/mtp:host=DEVICE/Internal shared storage/Documents/Filename.pdf'");
  }

  END {
      postprocess();
  }
  ```
  
  It was also tested with `adb push`, instead of `cp`.

  </details>
- Compile, and test if it copies the file to the tablet.
- Import your compiled PDF document into Notable, and choose to observe the PDF file.

> After each recompilation, Notable will detect the updated PDF and automatically refresh the view.

Instead of importing by hand, you can open the PDF with a [link](#links). The first time Notable
asks whether to observe or copy it. Choose to observe it: after that the same link jumps to the
page. A copy is not tied to the original file, so the link would ask to import it again.

```sh
adb push build/main.pdf /sdcard/Documents/main.pdf
adb shell am start -a android.intent.action.VIEW \
  -d "'notable://pdf?path=/sdcard/Documents/main.pdf&page=12'"
```

---

## Links

Notable opens `notable://` links, from a notes app such as Obsidian or Logseq, a browser, or `adb`.
A link only opens something; the one exception, importing a PDF that is not in Notable yet, asks
first.

| Link | Opens |
|---|---|
| `notable://page/<pageId>` | the page |
| `notable://book/<bookId>` | the notebook, at the page last open |
| `notable://pdf?path=<absolute path>&page=<N>` | the notebook that observes this PDF, at page N of the PDF (counted from 1; `page` is optional). If there is none, asks to import it into the library; only an observed PDF is found again by later links. If several notebooks observe it, the one edited last |

To get a link, use **Copy link to page** or **Copy link to book** in the editor menu, **Copy link**
in a page's menu, or **Copy link** in the notebook's settings. Exporting a single page copies its
link too. The link is copied as Markdown, `[Title](notable://…)`, or as an Org link; choose the
format in Settings → General. Org mode opens only link types it knows, so in Emacs register it
once:

```elisp
(org-link-set-parameters "notable" :follow (lambda (path) (browse-url (concat "notable:" path))))
```

In a path, encode spaces as `%20` (`/sdcard/My%20Notes/a.pdf`); a path typed with plain spaces
also works when the link is quoted. A `+` is a plus sign, not a space, so don't build links with
a form encoder (Python's `urlencode`); `urllib.parse.quote(path)` is right.

Links that work now keep working in later versions. Parameter names are case-sensitive, and
parameters Notable doesn't know are ignored, so new optional ones can be added. A link of a kind
your Notable doesn't know says that it needs a newer version.

---

## App Distribution
Notable is not distributed on Google Play or F-Droid. Official builds are provided exclusively via [GitHub Releases](https://github.com/Ethran/notable/releases).

---

## For Developers & Contributing

Contributions are welcome. **Read [CONTRIBUTING.md](./CONTRIBUTING.md) before opening a pull request.**

- Project file layout: see [docs/file-structure.md](./docs/file-structure.md)
- Data model and stroke encoding: see [docs/database-structure.md](./docs/database-structure.md)

> These documents were AI-generated and lightly verified; the code is the source of truth.

### Development Notes

- Debug builds need no signing setup. Release builds are signed in CI.
- To debug on a BOOX device, enable developer mode. You can follow [this guide](https://imgur.com/a/i1kb2UQ).

---

<!-- MARKDOWN LINKS -->
[logo]: https://github.com/Ethran/notable/blob/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png?raw=true "Notable Logo"
[license-shield]: https://img.shields.io/github/license/Ethran/notable.svg?style=for-the-badge

[license-url]: https://github.com/Ethran/notable/blob/main/LICENSE
[download-shield]: https://img.shields.io/github/v/release/Ethran/notable?style=for-the-badge&label=⬇️%20Download
[download-url]: https://github.com/Ethran/notable/releases/latest
[downloads-shield]: https://img.shields.io/github/downloads/Ethran/notable/total?style=for-the-badge&color=47c219&logo=cloud-download
[downloads-url]: https://github.com/Ethran/notable/releases/latest

[discord-shield]: https://img.shields.io/badge/Discord-Join%20Chat-7289DA?style=for-the-badge&logo=discord
[discord-url]: https://discord.gg/rvNHgaDmN2

[bug-url]: https://github.com/Ethran/notable/issues/new?template=bug_report.md
[feature-url]: https://github.com/Ethran/notable/issues/new?labels=enhancement
[bug-shield]: https://img.shields.io/badge/🐛%20Report%20Bug-red?style=for-the-badge
[feature-shield]: https://img.shields.io/badge/💡%20Request%20Feature-blueviolet?style=for-the-badge
