# WebDAV Sync - User Guide

## Overview

Notable supports WebDAV synchronization to keep your notebooks, pages, and drawings in sync across multiple devices. WebDAV is a standard protocol that works with many cloud storage providers and self-hosted servers.

## What Gets Synced?

- **Notebooks**: All your notebooks and their metadata
- **Pages**: Individual pages within notebooks
- **Strokes**: Your drawings and handwriting (stored in efficient SB1 binary format)
- **Images**: Embedded images in your notes
- **Backgrounds**: Custom page backgrounds
- **Folders**: Your folder organization structure

## Prerequisites

You'll need access to a WebDAV server. Common options include:

### Popular WebDAV Providers

1. **Nextcloud** (Recommended for self-hosting)
   - Free and open source
   - Full control over your data
   - URL format: `https://your-nextcloud.com/remote.php/dav/files/username/`

2. **ownCloud**
   - Similar to Nextcloud
   - URL format: `https://your-owncloud.com/remote.php/webdav/`

3. **Box.com**
   - Commercial cloud storage with WebDAV support
   - URL format: `https://dav.box.com/dav/`

4. **Other providers**
   - Many NAS devices (Synology, QNAP) support WebDAV
   - Some web hosting providers offer WebDAV access

## Setup Instructions

### 1. Get Your WebDAV Credentials

From your WebDAV provider, you'll need:
- **Server URL**: The full WebDAV endpoint URL
- **Username**: Your account username
- **Password**: Your account password or app-specific password

**Important**: Notable will automatically append `/notable` to your server URL to keep your data organized. For example:
- You enter: `https://nextcloud.example.com/remote.php/dav/files/username/`
- Notable creates: `https://nextcloud.example.com/remote.php/dav/files/username/notable/`

This prevents your notebooks from cluttering the root of your WebDAV storage.

### 2. Configure Notable

1. Open Notable
2. Go to **Settings** (three-line menu icon)
3. Select the **Sync** tab
4. Enter your WebDAV credentials:
   - **Server URL**: Your WebDAV endpoint URL
   - **Username**: Your account username
   - **Password**: Your account password
5. Click **Save Credentials**

### 3. Test Your Connection

1. Click the **Test Connection** button
2. Wait for the test to complete
3. You should see "✓ Connected successfully"
4. If connection fails, double-check your credentials and URL

### 4. Enable Sync

Toggle **Enable WebDAV Sync** to start syncing your notebooks.

## Sync Options

### Manual Sync
Click **Sync Now** to manually trigger synchronization. This will:
- Upload any local changes to the server
- Download any changes from other devices
- Resolve conflicts intelligently
  - Generally, last writer wins, including after deletions. If you make changes to a notebook after it has been deleted on any device, your notebook will be "resurrected" and re-created with the new changes.

### Automatic Sync
Enable **Automatic sync every X minutes** to sync periodically in the background.

### Sync on Note Close
Enable **Sync when closing notes** to automatically sync whenever you close a page. This ensures your latest changes are uploaded immediately.

## Advanced Features

### Force Operations (Use with Caution!)

Located under **CAUTION: Replacement Operations**:

- **Replace Server with Local Data**: Deletes everything on the server and uploads all local notebooks. Use this if the server has incorrect data.

- **Replace Local with Server Data**: Deletes all local notebooks and downloads everything from the server. Use this if your local data is corrupted.

**Warning**: These operations are destructive and cannot be undone! Make sure you know which copy of your data is correct before using these.

## Conflict Resolution

Notable handles conflicts intelligently:

### Notebook Deletion Conflicts
If a notebook is deleted on one device but modified on another device (while offline), Notable will **resurrect** the modified notebook instead of deleting it. This prevents accidental data loss.

### Timestamp-Based Sync
Notable uses timestamps to determine which version is newer:
- If local changes are newer → Upload to server
- If server changes are newer → Download to device
- Equal timestamps → No sync needed

## Sync Log

The **Sync Log** section shows real-time information about sync operations:
- Which notebooks were synced
- Upload/download counts
- Any errors that occurred
- Timestamps and performance metrics

Click **Clear** to clear the log.

## Troubleshooting

### Connection Failed

**Problem**: Test connection fails with "✗ Connection failed"

**Solutions**:
1. Verify your server URL is correct
2. Check username and password are accurate
3. Ensure you have internet connectivity
4. Check if your server requires HTTPS (not HTTP)
5. Try accessing the WebDAV URL in a web browser
6. Check if your server requires an app-specific password (common with 2FA)

### Sync Fails

**Problem**: Sync operation fails or shows errors in the log

**Solutions**:
1. Check the Sync Log for specific error messages
2. Verify you have sufficient storage space on the server
3. Try **Test Connection** again to ensure credentials are still valid
4. Check if the `/notable` directory exists on your server and is writable
5. Try force-downloading to get a fresh copy from the server

### Notebooks Not Appearing on Other Device

**Problem**: Synced on one device but not showing on another

**Solutions**:
1. Make sure both devices have sync enabled
2. Manually trigger **Sync Now** on both devices
3. Check the Sync Log on both devices for errors
4. Verify both devices are using the same server URL and credentials
5. Check the server directly (via web interface) to see if files were uploaded

### Very Slow Sync

**Problem**: Sync takes a long time to complete

**Solutions**:
1. This is normal for first sync with many notebooks
2. Subsequent syncs are incremental and much faster
3. Check your internet connection speed
4. Consider reducing auto-sync frequency
5. Large images or backgrounds may take longer to upload

### "Too Many Open Connections" Error

**Problem**: Sync fails with connection pool errors

**Solutions**:
1. Wait a few minutes and try again
2. Close and reopen the app
3. This usually resolves automatically

## Data Format

Notable stores your data on the WebDAV server in the following structure:

```
/notable/
├── deletions.json           # Tracks deleted notebooks
├── folders.json             # Folder hierarchy
└── notebooks/
    ├── {notebook-id-1}/
    │   ├── manifest.json    # Notebook metadata
    │   ├── pages/
    │   │   └── {page-id}.json
    │   ├── images/
    │   │   └── {image-file}
    │   └── backgrounds/
    │       └── {background-file}
    └── {notebook-id-2}/
        └── ...
```

### Efficient Storage

- **Strokes**: Stored as base64-encoded SB1 binary format with LZ4 compression for minimal file size
- **Images**: Stored as-is in their original format
- **JSON files**: Human-readable metadata

## Privacy & Security

- **Credentials**: Stored securely in Android's CredentialManager (encrypted storage)
- **Data in transit**: Uses HTTPS for secure communication (recommended)
- **Data at rest**: Depends on your WebDAV provider's security
- **No third-party cloud service**: Your data only goes to the WebDAV server you specify

## Best Practices

1. **Use HTTPS**: Always use `https://` URLs for security
2. **Regular syncs**: Enable automatic sync to avoid conflicts
3. **Backup**: Consider backing up your WebDAV storage separately
4. **Test first**: Use Test Connection before enabling sync
5. **Monitor logs**: Check Sync Log occasionally for any issues
6. **Dedicated folder**: The `/notable` subdirectory keeps things organized

## Getting Help

If you encounter issues:

1. Check the Sync Log for error details
2. Verify your WebDAV server is accessible
3. Try the troubleshooting steps above
4. Report issues at: https://github.com/Ethran/notable/issues

## Technical Details

For developers interested in how sync works internally, see:
- [Database Structure](database-structure.md) - Data storage formats including SB1
- [File Structure](file-structure.md) - Local file organization

---

**Version**: 1.0
**Last Updated**: 2026-01-18
