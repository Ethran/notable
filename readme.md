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

# Notable (Fork)

A maintained and customized fork of the archived [olup/notable](https://github.com/olup/notable) project.

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

- [About This Fork](#about-this-fork)
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
- [App Distribution](#app-distribution)
- [For Developers & Contributing](#for-developers--contributing)

</details>


---

## About This Fork
This project began as a fork of the original Notable app and has since evolved into a continuation of it. The architecture is largely the same, but many of the functions have been rewritten and expanded with a focus on practical, everyday use. Development is active when possible, guided by the principle that the app must be fast and dependable — performance comes first, and the basics need to feel right before new features are introduced. Waiting for things to load is seen as unacceptable, so responsiveness is a core priority.

Future plans include exploring how AI can enhance the app, with a focus on solutions that run directly on the device. A long-term goal is local handwriting conversion to LaTeX or plain text, making advanced features available without relying on external services.

---

## Features
* ⚡ **Fast page turns with caching:** smooth, swift page transitions, including quick navigation to the next and previous pages.
* ↕️ **Infinite vertical scroll:** a virtually endless canvas for notes with smooth vertical scrolling.
* 📝 **Quick Pages:** instantly create a new page.
* 📒 **Notebooks:** group related notes and switch easily between notebooks.
* 📁 **Folders:** organize notes with folders.
* 🤏 **Editor mode gestures:** [intuitive gesture controls](#gestures) to enhance editing.
* 🌅 **Images:** add, move, scale, and remove images.
* ➤ **Selection export:** export or share selected handwriting as PNG.
* ✏️ **Scribble to erase:** erase content by scribbling over it (disabled by default) — contributed by [@niknal357](https://github.com/niknal357).
* 🔄 **Auto-refresh on background change:** useful when using a tablet as a second display — see [Working with LaTeX](#working-with-latex).

---

## Download
**Download the latest stable version of the [Notable app here.](https://github.com/Ethran/notable/releases/latest)**

Alternatively, get the latest build from the main branch via the ["next" release](https://github.com/Ethran/notable/releases/next).

Open the **Assets** section of the release and select the `.apk` file.

<details><summary title="Click to show/hide details">❓ Where can I see alternative/older releases?</summary><br/>
You can go to the original olup <a href="https://github.com/olup/notable/tags" target="_blank">Releases</a> and download alternative versions of the Notable app.
</details>

<details><summary title="Click to show/hide details">❓ What is a 'next' release?</summary><br/>
The "next" release is a pre-release and may contain features implemented but not yet released as part of a stable version — and sometimes experiments that may not make it into a release.
</details>

---

## Gestures
Notable features intuitive gesture controls within Editor mode to optimize the editing experience:

#### ☝️ 1 Finger
* **Swipe up or down:** scroll the page.
* **Swipe left or right:** change to the previous/next page (only available in notebooks).
* **Double tap:** undo.
* **Hold and drag:** select text and images.

#### ✌️ 2 Fingers
* **Swipe left or right:** show or hide the toolbar.
* **Single tap:** switch between writing and eraser modes.
* **Pinch:** zoom in and out.
* **Hold and drag:** move the canvas.

#### 🔲 Selection
* **Drag:** move the selection.
* **Double tap:** copy the selected writing.

---

## System Requirements and Permissions
The app targets Onyx BOOX devices and requires Android 10 (SDK 29) or higher. Limited support for Android 9 (SDK 28) may be possible if [issue #93](https://github.com/Ethran/notable/issues/93) is resolved. Handwriting functionality is currently not available on non-Onyx devices. Enabling handwriting on other devices may be possible in the future but is not supported at the moment.

Storage access is required to manage notes, assets, and to observe PDF backgrounds, which need “all files access”. The database is stored at `Documents/natabledb` to simplify backups and reduce the risk of accidental deletion. Exports are written to `Documents/natable`.

---

## Export and Import

The app supports the following formats:

- **PDF** — export and import supported. You can also link a page to an external PDF so that changes on your computer are reflected live on the tablet (see [Working with LaTeX](#working-with-latex)).  
- **PNG** — export supported for handwriting selections, individual pages, and entire books.  
- **JPEG** — export supported for individual pages.  
- **XOPP** — export and import partially supported. Only stroke and image data are preserved; tool information for strokes may be lost when files are opened and saved with [Xournal++](https://xournalpp.github.io/). Backgrounds are not exported.  


---

## Roadmap

### Near-term
- Better selection tools:
  - Stroke editing (color, size, etc.)
  - Rotate and flip selection
  - Auto‑scroll when dragging a selection near screen edges
  - Easier selection movement, including dragging while scrolling
- PDF improvements:
  - Migration to a dedicated PDF library to replace the default Android renderer
  - Allow saving annotations back to the original PDF
  - Improved rendering and stability across devices

### Planned
- PDF annotation enhancements:
  - Display annotations from other programs
  - Additional quality‑of‑life tools for annotating imported PDFs

### Long-term
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

---

## App Distribution
Notable is not distributed on Google Play or F-Droid. Official builds are provided exclusively via [GitHub Releases](https://github.com/Ethran/notable/releases).

---

## For Developers & Contributing

- Project file layout: see [docs/file-structure.md](./docs/file-structure.md)  
- Data model and stroke encoding: see [docs/database-structure.md](./docs/database-structure.md)  
- Additional documentation will be added as needed  
  Note: These documents were AI-generated and lightly verified; refer to the code for the authoritative source.

### Development Notes

- Edit the `DEBUG_STORE_FILE` in `/app/gradle.properties` to point to your local keystore file. This is typically located in the `.android` directory.
- To debug on a BOOX device, enable developer mode. You can follow [this guide](https://imgur.com/a/i1kb2UQ).

Feel free to open issues or submit pull requests. I appreciate your help!

---

<!-- MARKDOWN LINKS -->
[logo]: https://github.com/Ethran/notable/blob/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png?raw=true "Notable Logo"
[contributors-shield]: https://img.shields.io/github/contributors/Ethran/notable.svg?style=for-the-badge
[contributors-url]: https://github.com/Ethran/notable/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/Ethran/notable.svg?style=for-the-badge
[forks-url]: https://github.com/Ethran/notable/network/members
[stars-shield]: https://img.shields.io/github/stars/Ethran/notable.svg?style=for-the-badge
[stars-url]: https://github.com/Ethran/notable/stargazers
[issues-shield]: https://img.shields.io/github/issues/Ethran/notable.svg?style=for-the-badge
[issues-url]: https://github.com/Ethran/notable/issues
[license-shield]: https://img.shields.io/github/license/Ethran/notable.svg?style=for-the-badge

[license-url]: https://github.com/Ethran/notable/blob/main/LICENSE
[download-shield]: https://img.shields.io/github/v/release/Ethran/notable?style=for-the-badge&label=⬇️%20Download
[download-url]: https://github.com/Ethran/notable/releases/latest
[downloads-shield]: https://img.shields.io/github/downloads/Ethran/notable/total?style=for-the-badge&color=47c219&logo=cloud-download
[downloads-url]: https://github.com/Ethran/notable/releases/latest

[discord-shield]: https://img.shields.io/badge/Discord-Join%20Chat-7289DA?style=for-the-badge&logo=discord
[discord-url]: https://discord.gg/rvNHgaDmN2
[kofi-shield]: https://img.shields.io/badge/Buy%20Me%20a%20Coffee-ko--fi-ff5f5f?style=for-the-badge&logo=ko-fi&logoColor=white
[kofi-url]: https://ko-fi.com/rethran

[sponsor-shield]: https://img.shields.io/badge/Sponsor-GitHub-%23ea4aaa?style=for-the-badge&logo=githubsponsors&logoColor=white
[sponsor-url]: https://github.com/sponsors/rethran

[docs-url]: https://github.com/Ethran/notable
[bug-url]: https://github.com/Ethran/notable/issues/new?template=bug_report.md
[feature-url]: https://github.com/Ethran/notable/issues/new?labels=enhancement&template=feature-request---.md
[bug-shield]: https://img.shields.io/badge/🐛%20Report%20Bug-red?style=for-the-badge
[feature-shield]: https://img.shields.io/badge/💡%20Request%20Feature-blueviolet?style=for-the-badge
