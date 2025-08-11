package com.ethran.notable.utils

import android.content.Context
import android.content.pm.PackageManager
import com.ethran.notable.BuildConfig
import com.ethran.notable.TAG
import com.ethran.notable.classes.showHint
import io.shipbook.shipbooksdk.Log
import kotlinx.serialization.json.Json
import java.net.URL
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date

@Suppress("PropertyName")
@kotlinx.serialization.Serializable
data class GitHubRelease(
    val name: String,
    val html_url: String,
    val prerelease: Boolean,
    val assets: List<Asset> = emptyList()
) {
    @kotlinx.serialization.Serializable
    data class Asset(
        val name: String,
        val updated_at: String
    )
}


data class Version(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val buildTimestamp: Date? = null
) : Comparable<Version> {
    companion object {
        private val stableVersionRegex = Regex("""^(\d+)\.(\d+)\.(\d+)$""")
        private val nextVersionRegex =
            Regex("""^(\d+)\.(\d+)\.(\d+)-next-(\d{2}\.\d{2}\.\d{4}-\d{2}:\d{2})$""")
        private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy-HH:mm")

        fun fromString(versionString: String): Version? {
            nextVersionRegex.find(versionString)?.let { match ->
                val (major, minor, patch, dateStr) = match.destructured
                return try {
                    val localDateTime = LocalDateTime.parse(dateStr, dateFormatter)
                    val instant = localDateTime.atOffset(ZoneOffset.UTC).toInstant()
                    val timestamp = Timestamp.from(instant)
                    return Version(major.toInt(), minor.toInt(), patch.toInt(), timestamp)
                } catch (e: Exception) {
                    null
                }
            }
            stableVersionRegex.find(versionString)?.let { match ->
                val (major, minor, patch) = match.destructured
                return Version(major.toInt(), minor.toInt(), patch.toInt())
            }
            return null
        }

        fun fromTimestamp(timestampMillis: Long): Version {
            return Version(0, 0, 0, Timestamp(timestampMillis))
        }

    }

    override fun compareTo(other: Version): Int {
        if (this.buildTimestamp != null && other.buildTimestamp != null) {
            return this.buildTimestamp.compareTo(other.buildTimestamp)
        }
        if (this.major != other.major) {
            return this.major.compareTo(other.major)
        }
        if (this.minor != other.minor) {
            return this.minor.compareTo(other.minor)
        }
        return this.patch.compareTo(other.patch)
    }
    override fun toString(): String {
        return if (buildTimestamp != null && isNext) {
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
                .withZone(ZoneId.systemDefault())
            val instant = buildTimestamp.toInstant()
            formatter.format(instant)
        } else {
            "v$major.$minor.$patch"
        }
    }
}

private val jsonParser = Json { ignoreUnknownKeys = true }

fun getLatestReleaseVersion(repoOwner: String, repoName: String): String? {
    val apiUrl = "https://api.github.com/repos/$repoOwner/$repoName/releases"
    val json = URL(apiUrl).readText()
    val versions = jsonParser.decodeFromString<List<GitHubRelease>>(json)

    versions.forEach {
        if (!it.prerelease) {
            // Check if the tag name starts with "v" and remove it if necessary
            return if (it.name.startsWith("v")) {
                it.name.substring(1)
            } else {
                it.name
            }
        }
    }

    return null
}

fun getLatestPreReleaseTimestamp(owner: String, repo: String): Long? {
    val apiUrl = "https://api.github.com/repos/$owner/$repo/releases"
    val json = URL(apiUrl).readText()
    val releases = jsonParser.decodeFromString<List<GitHubRelease>>(json)
    val preRelease = releases.firstOrNull { it.prerelease } ?: return null

    val asset = preRelease.assets.firstOrNull { it.name == "notable-next.apk" } ?: return null

    val formatter = DateTimeFormatter.ISO_DATE_TIME
    // 900 000ms = 15minutes, added to compensate for compilation time.
    return asset.updated_at.let {
        // Parse with timezone information from GitHub
        val zonedDateTime = java.time.ZonedDateTime.parse(it, formatter)
        // Convert to system default timezone
        zonedDateTime.withZoneSameInstant(ZoneId.systemDefault()).toInstant().toEpochMilli() - 900000
    }
}

fun getCurrentVersionName(context: Context): String? {
    try {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return packageInfo.versionName
    } catch (e: PackageManager.NameNotFoundException) {
        e.printStackTrace()
    }
    return null
}

// cache
var isLatestVersion: Boolean? = null

fun isLatestVersion(context: Context, force: Boolean = false): Boolean {
    if (!force && isLatestVersion != null) return isLatestVersion!!

    try {
        val isNextBuild = BuildConfig.VERSION_NAME.contains("next")


        val currentVersion = getCurrentVersionName(context)

        if (isNextBuild) {
            val latestVersion = getLatestPreReleaseTimestamp("ethran", "notable")
            //        // If either version is null, we can't compare them
            if (latestVersion == null || currentVersion == null) {
                throw Exception("One of the version is null - comparison is impossible")
            }
            Log.i(TAG, "Version is $currentVersion and latest on repo is $latestVersion")
            val latest = Version.fromTimestamp(latestVersion)

            val current = Version.fromString(currentVersion)
                ?: throw Exception(
                    "One of the version doesn't match simple semantic - comparison is impossible"
                )


            // If either version does not fit simple semantic version don't compare

            isLatestVersion = current.compareTo(latest) != -1
            if (!isLatestVersion!!) {
                showHint(
                    "A newer preview version is available!\n" +
                            "You are using released $current, and newest is from ${latest}.",
                    duration = 5000
                )
            }
            return isLatestVersion!!
        } else {
            val latestVersion = getLatestReleaseVersion("ethran", "notable")
            //        // If either version is null, we can't compare them
            if (latestVersion == null || currentVersion == null) {
                throw Exception("One of the version is null - comparison is impossible")
            }
            val latest = Version.fromString(latestVersion)
            val current = Version.fromString(currentVersion)


            // If either version does not fit simple semantic version don't compare
            if (latest == null || current == null) {
                throw Exception(
                    "One of the version doesn't match simple semantic - comparison is impossible"
                )
            }

            isLatestVersion = current.compareTo(latest) != -1
            if (!isLatestVersion!!) {
                showHint(
                    "A newer stable version is available!\n" +
                            "You are using ${current}, while the latest is $latest.",
                    duration = 5000
                )
            }
            return isLatestVersion!!
        }


    } catch (e: Exception) {
        Log.i(TAG, "Failed to fetch latest release version: ${e.message}")
        return true
    }
}

const val isNext = BuildConfig.IS_NEXT
