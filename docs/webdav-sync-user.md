# WebDAV Sync — User Guide

## Overview

Notable can sync notebooks across devices through a cloud or self-hosted WebDAV server. Sync is
experimental, so keep an independent backup—especially before using a replacement operation.

## What Gets Synced

- Notebooks, their order, and their settings
- Pages, handwriting, drawings, and inserted images
- Folder organization and notebook moves
- Notebook deletions

Not synced:

- Standalone Quick Pages
- Custom page backgrounds: background images and PDFs (imported or linked). The page keeps its
  background setting, but the file itself must be available on each device. Built-in templates
  (lined, grid, …) work everywhere.
- Folder deletions; a deleted folder may reappear after another device syncs

Changes to different pages merge automatically. If both devices change the same page or the notebook
structure, Notable stops and asks which version to keep. It cannot merge individual strokes within a
page. See [Conflict Resolution](#conflict-resolution).

## Prerequisites

You need a WebDAV account and its file endpoint URL. Use HTTPS unless the server is on a network you
fully trust.

### Common Providers

- **Nextcloud:** copy the personal WebDAV URL from Files settings. It is usually
  `https://host/remote.php/dav/files/username/`. Some installations use
  `https://host/remote.php/webdav/`. Nextcloud recommends an app password; see its
  [WebDAV guide](https://docs.nextcloud.com/server/latest/user_manual/en/files/access_webdav.html).
- **ownCloud:** commonly uses `https://host/remote.php/webdav/`.
- **NAS and hosting providers:** copy the exact URL from the provider's settings because paths vary.

## Set Up Sync

### 1. Get Your Credentials

Collect the server URL, username, and password. If your account uses two-factor authentication, use
an app password.

Notable appends `/notable` to the URL:

- You enter: `https://cloud.example.com/remote.php/dav/files/alex/`
- Notable uses: `https://cloud.example.com/remote.php/dav/files/alex/notable/`

Don't add the trailing `/notable` yourself, or Notable will create `…/notable/notable/`.

#### Nextcloud with Two-Factor Authentication

Create an app password instead of using your regular password:

1. Log in to Nextcloud
2. Go to **Settings** → **Security**
3. Under **Devices & sessions**, click **Create new app password**
4. Name it "Notable"
5. Use the generated credentials in Notable

### 2. Configure Notable

1. Open **Settings → Sync**
2. Enter your **Server URL**, **Username**, and **Password** (or app password)
3. Tap **Save Credentials**

Once a password is saved the field shows **(unchanged)**; leave it empty to keep the saved password.

### 3. Test Your Connection

1. Tap **Test Connection**.
2. If it fails, check the URL and credentials, then see [Troubleshooting](#troubleshooting).

The test checks authentication and server time. When the connection works, it also runs a short
check of how the server reports file changes: it writes and then deletes a few small files in
`/notable/.capability-probe/`. The result appears under **Server capabilities** and decides whether
**Fast change detection** can be used on this server (see [Sync Options](#sync-options)). Run the
test again after changing the server URL or username.

The test does not check quota or every WebDAV operation, so a successful test does not guarantee
that sync will succeed.

Sync stops when the device and server clocks differ by more than 30 seconds. Enable automatic date
and time if necessary.

### 4. Enable Sync

1. Toggle **Sync WebDAV** (while sync is off, the Sync tab first shows an experimental-feature
   warning to accept)
2. Run **Sync Now** once and check the Activity Log before enabling automatic sync

## Sync Options

| Option | Behavior |
|---|---|
| **Sync Now** | Runs a full sync. "Last sync" changes only after the entire run succeeds. Other notebooks can still transfer if one fails. |
| **Auto sync every … minutes** | Schedules background sync; the **Sync interval** selector sets 15–240 minutes in 5-minute steps. Android may delay it for battery or network reasons. |
| **Sync when the app starts** | Schedules a full sync when Notable's main screen starts. |
| **Sync when closing notes** | Syncs only the notebook you closed. It skips the attempt if another sync is running. |
| **Check for a newer version when opening a notebook** | Checks the server's copy and, when it is newer, offers **Sync now**, which closes the notebook and syncs it. Network and authentication errors do not block opening the notebook. |
| **Fast change detection** | On servers where Test Connection found it safe, a full sync skips checking notebooks whose server folder has not changed. Turn it off to force the standard per-notebook check. It has no effect until Test Connection has run. |
| **Sync on WiFi only (no mobile data)** | Requires a network Android reports as unmetered, usually Wi-Fi or Ethernet. |

Every sync first saves the open notebook's pending edits; if they cannot be saved, the sync stops and
tries again later.

### Upload Only / Download Only

These two modes are mutually exclusive — turning one on turns the other off.

- **Upload only** pushes local changes but does not download notebooks or apply remote deletions. It
  skips newer server copies and marks them **Newer on server**. Folders are still merged into the
  server's `folders.json`, but server folder changes are not applied locally.

- **Download only** pulls server changes but does not upload notebooks, folders, or local deletions.
  Local edits remain **Not synced**, and a notebook deleted only on this device is downloaded again.
  Cleanup may still delete server deletion markers older than 90 days, so this is not a strictly
  read-only mode.

### Cancel a Running Sync

**Cancel sync** (shown while a sync runs) stops WorkManager sync jobs, although an active network
request may take time to finish.
It also removes periodic sync for the current app session; restart Notable to restore the schedule.
It may not stop sync-on-close or check-on-open.

## Sync Status Badges

Each notebook cover in the library shows a small icon reflecting its sync status:

| Icon | Status | Meaning |
|---|---|---|
| ☁️✓ | **Synced** | Local data matches the last successful notebook sync. This is not a live server check. |
| ☁️✕ | **Not synced** | No sync record exists or the notebook has local changes. |
| 🕐 | **Scheduled** | A full sync is running and this notebook is waiting its turn. |
| 🔄 | **Syncing** | This notebook is transferring. |
| ☁️⬇ | **Newer on server** | Upload-only mode skipped a newer server copy. |
| ⚠️ | **Error** | The last attempt failed. Check the Activity Log. |
| 🔄❗ | **Conflict** | Both devices changed the same page or notebook structure. Tap the notebook to resolve it. |

Badges describe the last recorded state, not the server's current state. A notebook may briefly show
**Not synced** after an upgrade until the next sync rebuilds its status.

## Replacement Operations

These operations appear under **DANGER ZONE**, ask for confirmation, and cannot be undone.

- **Upload All** (*Replace Server Data?*) uploads every local notebook and `folders.json`, then
  deletes server notebooks that do not exist locally (leaving deletion markers so other devices
  remove them too). If the local library looks incomplete — at least 10 server notebooks missing
  locally and more than half of the server's — it keeps them and reports an error instead. A partial
  failure can leave mixed old and new server data.

- **Download All** (*Replace Local Data?*) refuses to run if the server has no notebooks. Otherwise
  it updates folders from the server, downloads every server notebook over the local copy (skipping
  ones already identical, so an interrupted run resumes), and only then deletes local notebooks and
  folders that are not on the server. It is **not transactional**: a failure can leave a partial
  restore.

Back up your data and confirm which copy is authoritative before continuing.

## Conflict Resolution

When both copies change, the newer notebook normally wins. If their timestamps are within one second,
Notable compares what changed:

- **Different pages** merge automatically.
- **The same page on both devices**, or a **structural change** (pages added, removed, reordered, or
  renamed, or notebook settings changed), creates a **Conflict** badge and waits for your choice.

### Resolving a Conflict

Tap a notebook with a **Conflict** badge to open the resolution dialog.

For each page conflict, choose:

- **Keep mine** — upload your version, replacing the server copy.
- **Use server** — download the server version over yours.
- **Skip** — decide later; the page stays flagged until the next sync.

Resolve a structural conflict for the entire notebook with **Keep mine** or **Use server**.

After the last conflict is resolved, Notable syncs and clears the badge. On failure, the dialog stays
open and displays the error.

Conflict resolution requires two-way sync. Switch off **Upload only** or **Download only** before
resolving; the badge remains until then.

Notable cannot merge changes within one page. Sync before switching devices and avoid editing the
same notebook on two devices at once.

### Deletion Conflicts

If one device deletes a notebook while another makes a later edit, Notable keeps and re-uploads the
edited copy. This protects the edit but can make a deletion reappear.

## Activity Log

The **Activity Log** in the Sync settings shows transfers and errors. It keeps the latest 50 entries
in memory, so copy a failure promptly with **Copy Log**. Tap **Clear Log** to empty it.

## Troubleshooting

### Connection Succeeds but Sync Fails

1. Confirm the entered URL is the writable WebDAV files endpoint, not a normal website page
2. Confirm the account can create a folder and upload a file outside of Notable
3. Use an app password when your provider requires one (see
   [Nextcloud with two-factor authentication](#nextcloud-with-two-factor-authentication))
4. Check server quota and Android device storage
5. Confirm the server supports `HEAD`, `PROPFIND` and `MOVE`
6. Copy the Activity Log before older entries disappear

### Notebooks Not Appearing on Other Device

1. Run **Sync Now** on the source device and wait for success
2. Run **Sync Now** on the destination device
3. Confirm both devices use exactly the same base URL and account
4. Look under `/notable/notebooks/{id}/` in the provider's file UI
5. Check for **Error**, **Not synced**, or **Newer on server** status
6. Confirm the notebook isn't a standalone Quick Page — those never sync

### A Notebook Is Missing an Image or Background

Missing media does not fail the whole notebook sync and is not fetched automatically if it appears
later.

1. For an inserted image: on the device that still has the file, open the affected notebook, edit
   the page that holds it, and sync — this re-uploads the media
2. Custom backgrounds (images and PDFs) are not synced — make the same file available separately on
   each device

### Very Slow Sync

The first sync transfers every page and media file. Later runs transfer only changed pages, although
large images and pages still take time.

1. Wait for the first sync to finish; later runs should be faster
2. Check your internet connection speed
3. Consider reducing auto-sync frequency

### A Deletion Reappears

Run a normal full sync on the device that deleted the notebook, before its deletion tombstone ages
out after 90 days. Folder deletions are not propagated reliably and may reappear.

### Safe Recovery Order

When something looks wrong and you're not sure which side to trust:

1. Stop editing the affected notebook on all devices
2. Make an external backup of the known-good side
3. Test the connection and inspect the Activity Log
4. Try an ordinary **Sync Now**
5. Only use a replacement operation once you're certain which side is authoritative

## Privacy and Security

- Notable encrypts the saved password with an AndroidKeyStore-backed key.
- Use HTTPS. HTTP exposes credentials and notebook data to the network.
- Notable does not end-to-end encrypt server data; security, retention, and backups depend on the
  provider.
- Data goes only to the WebDAV server you configure.

## Best Practices

1. Use HTTPS unless you fully trust the network.
2. Sync before switching devices.
3. Keep a separate backup, especially before replacement operations.
4. Check and copy the Activity Log promptly after a failure.

## Getting Help

1. Check the Activity Log for error details
2. Verify your WebDAV server is accessible and writable outside of Notable
3. Try the troubleshooting steps above
4. [Report an issue](https://github.com/Ethran/notable/issues)

## Technical Details

See [WebDAV Sync — Technical Documentation](webdav-sync-technical.md) for the architecture, protocol,
data formats, and conflict-resolution design.
