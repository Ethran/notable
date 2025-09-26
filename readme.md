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
- [Supported Devices](#supported-devices)
- [Roadmap](#roadmap)
- [Screenshots](#screenshots)
- [Working with LaTeX](#working-with-latex)
- [Contribute](#contribute)

</details>


---

## About This Fork
This fork is maintained by **Ethran** as a continuation and personal enhancement of the original Notable app. Development is semi-active and tailored toward personal utility while welcoming community contributions.

### What's New?
- Regular updates and experimental features
- Improved usability and speed
- Custom features suited for e-ink devices and note-taking

> ⚠️ Note: Features may reflect personal preferences.

---

## Features
* ⚡ **Fast Page Turn with Caching:** Notable leverages caching techniques to ensure smooth and swift page transitions, allowing you to navigate your notes seamlessly, including quick navigation to the next and previous pages.
* ↕️ **Infinite Vertical Scroll:** Enjoy a virtually endless canvas for your notes. Scroll vertically without limitations. You can even enjoy smooth scrolling.
* 📝 **Quick Pages:** Quickly create a new page using the Quick Pages feature.
* 📒 **Notebooks:** Keep related notes together and easily switch between different notebooks based on your needs.
* 📁 **Folders:** Create folders to organize your notes.
* 🤏 **Editor Mode Gestures:** [Intuitive gesture controls](#gestures) to enhance the editing experience.
* 🌅 **Images:** Add, move, scale, and remove images.
* ➤ **Selection export:** Share selected text.
* ✏️ **Scribble to Erase:** Erase content naturally by scribbling over it (disabled by default) – *Contributed by [@niknal357](https://github.com/niknal357)*
* 🔄 **Refresh on background change:** Can turn your tablet into a second display — see [Working with LaTeX](#working-with-latex).

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
* **Swipe up or down:** Scroll the page.
* **Swipe left or right:** Change to the previous/next page (only available in notebooks).
* **Double tap:** Undo.
* **Hold and drag:** Select text and images.

#### ✌️ 2 Fingers
* **Swipe left or right:** Show or hide the toolbar.
* **Single tap:** Switch between writing and eraser modes.
* **Pinch:** Zoom in and out.
* **Hold and drag:** Move the canvas.

#### 🔲 Selection
* **Drag:** Move the selection.
* **Double tap:** Copy the selected writing.

## Supported Devices

The following table lists devices confirmed by users to be compatible with specific versions of Notable.  
This does not imply any commitment from the developers.

| Device Name                                                                           | v0.0.10 | v0.0.11dev                                   | v0.0.14+ |
|---------------------------------------------------------------------------------------|---------|-----------------------------------------------|----------|
| [ONYX Boox Go 10.3](https://onyxboox.com/boox_go103)                                  | ✔       | ?                                             | ✔        |
| [Onyx Boox Note Air 4 C](https://onyxboox.pl/en/ebook-readers/onyx-boox-note-air-4-c) | ✘       | ✔                                             | ✔        |
| [Onyx Boox Note Air 3 C](https://onyxboox.pl/en/ebook-readers/onyx-boox-note-air-3-c) | ✘       | ✔                                             | ✔        |
| [Onyx Boox Note Max](https://shop.boox.com/products/notemax)                          | ✘       | ✔                                             | ✔        |
| [Boox Note 3](https://onyxboox.pl/en/ebook-readers/onyx-boox-note-3)                  | ✔       | ✘ ([issue #24](https://github.com/Ethran/notable/issues/24)) | ✔        |

Feel free to add your device if tested successfully!

## Roadmap

Features I’d like to implement in the future (some might take a while — or a long while):

- [ ] Bookmarks support, tags, and internal links — [Issue #52](https://github.com/Ethran/notable/issues/52)
  - [ ] Export links to PDF

- [x] Better notebook covers, provide default styles of title page

- [ ] PDF annotation
  - [x] Basic support
  - [ ] Show annotations from other programs
  - [ ] Allow saving annotations to the original PDF file

- [ ] Figure and text recognition — [Issue #44](https://github.com/Ethran/notable/issues/44)
  - [ ] Searchable notes
  - [ ] Automatic creation of tag descriptions
  - [ ] Shape recognition

- [x] Moving the page horizontally — it would be nice to write in the margins.

- [ ] Better selection tools
  - [ ] Stroke editing: color, size, etc.
  - [ ] Rotate
  - [ ] Flip selection
  - [ ] Auto-scroll when dragging selection to screen edges
  - [ ] Easier selection movement (e.g., dragging to scroll page)

- [x] More dynamic page and notebook movement. Currently, pages can only be moved left/right — add drag-and-drop support

- [!] Custom drawing tools: not possible.

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

## Contribute

Notable is an open-source project, and contributions are welcome. If you'd like to get started, please refer to [GitHub's contributing guide](https://docs.github.com/en/get-started/quickstart/contributing-to-projects).

### Project structure
Project structure can be found [here](./docs/file-structure.md).

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

[license-url]: https://github.com/Ethran/notable/blob/main/LICENSE.txt <!-- To be added. -->
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
