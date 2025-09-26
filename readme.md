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
- [Project Philosophy and AI](#project-philosophy-and-ai)
- [Features](#features)
- [Download](#download)
- [Gestures](#gestures)
- [System Requirements and Permissions](#system-requirements-and-permissions)
- [Export and Import](#export-and-import)
- [Roadmap](#roadmap)
- [Known Limitations](#known-limitations)
- [Troubleshooting and FAQ](#troubleshooting-and-faq)
- [Screenshots](#screenshots)
- [Working with LaTeX](#working-with-latex)
- [App Distribution](#app-distribution)
- [Contribute](#contribute)

</details>


---

## About This Fork
This project began as a fork of the original Notable app and has since evolved into a continuation of it. The architecture is largely the same, but many of the functions have been rewritten and expanded with a focus on practical, everyday use. Development is active when possible, guided by the principle that the app must be fast and dependable — performance comes first, and the basics need to feel right before new features are introduced. Waiting for things to load is seen as unacceptable, so responsiveness is a core priority.

The stance on AI is simple: no reliance on cloud integrations. The preference is for solutions that run entirely on the device, with the long-term goal of enabling local handwriting-to-LaTeX conversion (or at least to plain text).

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

* **Android version**: Requires Android 10 (SDK 29) or higher. Limited support for Android 9 (SDK 28) may be possible if [issue #93](https://github.com/Ethran/notable/issues/93) is resolved.
* **Device support**: Optimized for Onyx BOOX devices. Handwriting features are currently not available on non-Onyx devices, though future support may be possible.
* **Permissions and storage**: Storage access is needed to manage notes, assets, and PDF backgrounds (which require “all files access”). The database is stored in `Documents/natabledb` for easy backup and safer handling, while exports are saved in `Documents/natable`.


---

## Export and Import

- Selection export: export or share selected handwriting as PNG.
- PDF:
  - Import PDFs and optionally observe them for live refresh (see [Working with LaTeX](#working-with-latex)).
- Xournal++:
  - XOPP export for [Xournal++](https://xournalpp.github.io/) — partial support.  
    Files opened and saved by Xournal++ may lose some stroke data. Background information is not exported correctly.

---

## Roadmap

- [ ] Bookmarks support, tags, and internal links — [issue #52](https://github.com/Ethran/notable/issues/52) — long-term  
  - [ ] Export links to PDF — long-term

- [ ] PDF annotation and other PDF-related improvements
  - [x] Basic support
  - [ ] Show annotations from other programs
  - [ ] Allow saving annotations to the original PDF file
  - [ ] Migrate to a dedicated PDF library (replacing the default Android renderer)

- [ ] Figure and text recognition — [issue #44](https://github.com/Ethran/notable/issues/44)  
  - [ ] Searchable notes — long-term  
  - [ ] Automatic creation of tag descriptions — long-term  
  - [ ] Shape recognition — long-term

- [ ] Better selection tools  
  - [ ] Stroke editing: color, size, etc.  
  - [ ] Rotate  
  - [ ] Flip selection  
  - [ ] Auto-scroll when dragging selection to screen edges  
  - [ ] Easier selection movement (e.g., dragging to scroll page)

- [x] More dynamic page and notebook movement. Previously, pages could only be moved left/right — drag-and-drop support added.

- [x] Page can be moved horizontally — makes it easier to write in the margins.

- [x] Better notebook covers, with default title-page styles.

- [!] Custom drawing tools: not possible.

---

## Known Limitations

- Custom drawing tools are not supported because the Onyx E‑Ink library does not support them, and its documentation is limited.
- Non-Onyx devices currently lack handwriting support.
- Some advanced Onyx-specific features depend on vendor libraries and may not behave consistently across firmware versions.

---

## Troubleshooting and FAQ

**What are “NeoTools,” and why are some disabled?**
NeoTools are components of the Onyx E-Ink toolset, made available through Onyx’s libraries. However, certain tools are unstable and can cause crashes, so they are disabled by default to ensure better app stability. Examples include:

* `com.onyx.android.sdk.pen.NeoCharcoalPenV2`
* `com.onyx.android.sdk.pen.NeoMarkerPen`
* `com.onyx.android.sdk.pen.NeoBrushPen`


---

## Screenshots

<div style="display: flex; flex-wrap: wrap; gap: 10px;">
  <img src="https://github.com/user-attachments/assets/c3054254-043b-4cce-8524-43d10505ad0b" alt="screenshot-1" width="200"/>
  <img src="https://github.com/user-attachments/assets/c23119b7-cdae-4742-83f2-a4f39863c571" alt="screenshot-3" width="200"/>
  <img src="https://github.com/user-attachments/assets/9f3e7012-69e4-4125-bf69-509b52e1ebaf" alt="screenshot-5" width="200"/>
  <img src="https://github.com/user-attachments/assets/24c8c750-eb8e-4f01-ac62-6a9f8e5f9e4f" alt="screenshot-6" width="200"/>
  <img src="https://github.com/user-attachments/assets/4cdb0e74-bfce-4dba-bc21-886a5834401e" alt="screenshot-7" width="200"/>
  <img src="https://github.com/user-attachments/assets/857f16d3-e59c-48ca-99b5-577d96ef33e0" alt="screenshot-7" width="200"/>
  <img src="https://github.com/user-attachments/assets/e8304495-dbab-4d7a-987a-b76bf91a3a74" alt="screenshot-7" width="200"/>
  <img src="https://github.com/user-attachments/assets/38226966-0e19-45c9-a318-a8fd9d8edf02" alt="screenshot-7" width="200"/>
  <img src="https://github.com/user-attachments/assets/df29f77c-94a8-4c56-bbd4-d7285654df30" alt="screenshot-7" width="200"/>
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

  </details>
- Compile, and test if it copies the file to the tablet.
- Import your compiled PDF document into Notable, and choose to observe the PDF file.

> After each recompilation, Notable will detect the updated PDF and automatically refresh the view.

---

## App Distribution

- **Not available** on Google Play or F-Droid.
- Official builds are provided only via [GitHub Releases](https://github.com/Ethran/notable/releases).

---

## Contribute

Notable is an open-source project, and contributions are welcome. If you'd like to get started, please refer to [GitHub's contributing guide](https://docs.github.com/en/get-started/quickstart/contributing-to-projects).

### Project structure and docs
- Project structure: [docs/file-structure.md](./docs/file-structure.md)  
- Database structure and stroke encoding specification: [docs/database-structure.md](./docs/database-structure.md)  
  Note: This documents ware AI-generated and lightly verified; refer to the code for the authoritative source.

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
