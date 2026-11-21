package com.belugachat.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.belugachat.app.BuildConfig
import com.belugachat.app.data.SettingsStore
import com.belugachat.app.llm.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

data class UpdateRelease(
    val version: String,
    val tagName: String,
    val notes: String,
    val htmlUrl: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val apkSha256: String? = null,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: UpdateRelease, val fromAutoCheck: Boolean = false) : UpdateState
    data class Downloading(val release: UpdateRelease, val bytesRead: Long, val totalBytes: Long) : UpdateState
    data class ReadyToInstall(val release: UpdateRelease, val apkFile: File) : UpdateState
    data class Error(val message: String, val release: UpdateRelease? = null) : UpdateState
}

class AppUpdater(
    private val context: Context?,
    private val settings: SettingsStore?,
    private val http: OkHttpClient = Http.client,
    private val apiBaseUrl: String = "https://api.github.com",
    private val repo: String = BuildConfig.GITHUB_REPO,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val enabled: Boolean = BuildConfig.ENABLE_SELF_UPDATE,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var downloadJob: Job? = null

    suspend fun fetchLatestRelease(): UpdateRelease? = withContext(Dispatchers.IO) {
        val url = "${apiBaseUrl.trimEnd('/')}/repos/$repo/releases/latest"
        val requireHttps = apiBaseUrl.startsWith("https://", ignoreCase = true)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "BelugaChat-Android/$currentVersion")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return@use null
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("GitHub returned HTTP ${response.code}")
            }
            parseLatestRelease(body, requireHttps = requireHttps)
        }
    }

    suspend fun checkAutomatically(nowMs: Long = System.currentTimeMillis()) {
        if (!enabled) return
        val currentSettings = runCatching { settings?.current() }.getOrNull()
        val lastCheckMs = currentSettings?.lastUpdateCheckMs ?: 0L
        if (!shouldAutoCheck(lastCheckMs, nowMs, enabled)) return
        val currentState = _state.value
        if (currentState !is UpdateState.Idle && currentState !is UpdateState.UpToDate) return

        runCatching {
            val release = fetchLatestRelease()
            settings?.setLastUpdateCheckMs(nowMs)
            if (
                release != null &&
                isNewer(release.version, currentVersion) &&
                release.version != currentSettings?.dismissedUpdateVersion
            ) {
                _state.value = UpdateState.Available(release, fromAutoCheck = true)
            }
        }
    }

    suspend fun checkManually(nowMs: Long = System.currentTimeMillis()) {
        if (!enabled) {
            _state.value = UpdateState.UpToDate
            return
        }
        val active = _state.value
        if (active is UpdateState.Checking || active is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        try {
            val release = fetchLatestRelease()
            settings?.setLastUpdateCheckMs(nowMs)
            _state.value = if (release != null && isNewer(release.version, currentVersion)) {
                UpdateState.Available(release, fromAutoCheck = false)
            } else {
                UpdateState.UpToDate
            }
        } catch (e: CancellationException) {
            _state.value = UpdateState.Idle
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Error(e.message ?: "Could not check for updates.")
        }
    }

    fun startDownload(release: UpdateRelease, scope: CoroutineScope) {
        val ctx = context ?: return
        downloadJob?.cancel()
        downloadJob = scope.launch {
            downloadApk(ctx, release)
        }
    }

    private suspend fun downloadApk(ctx: Context, release: UpdateRelease) {
        val updatesDir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val safeVersion = release.version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val targetFile = File(updatesDir, "belugachat-$safeVersion.apk")
        val partFile = File(updatesDir, "belugachat-$safeVersion.apk.part")

        _state.value = UpdateState.Downloading(release, 0L, release.apkSizeBytes)
        try {
            withContext(Dispatchers.IO) {
                updatesDir.listFiles()?.forEach { existing ->
                    if (existing.absolutePath != targetFile.absolutePath) existing.delete()
                }
                if (targetFile.exists() && release.apkSizeBytes > 0 && targetFile.length() == release.apkSizeBytes) {
                    val expected = release.apkSha256
                    if (expected == null || sha256Hex(targetFile).equals(expected, ignoreCase = true)) {
                        return@withContext
                    }
                }
                targetFile.delete()
                partFile.delete()

                val request = Request.Builder()
                    .url(release.apkUrl)
                    .header("User-Agent", "BelugaChat-Android/$currentVersion")
                    .get()
                    .build()

                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Download failed (HTTP ${response.code})")
                    }
                    val body = response.body ?: throw IOException("Empty download response.")
                    val total = body.contentLength().takeIf { it > 0L } ?: release.apkSizeBytes
                    var read = 0L
                    var lastEmit = 0L
                    val buffer = ByteArray(16 * 1024)
                    val digest = MessageDigest.getInstance("SHA-256")
                    body.byteStream().use { input ->
                        FileOutputStream(partFile).use { output ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count == -1) break
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                read += count
                                val now = System.currentTimeMillis()
                                if (now - lastEmit >= 80L) {
                                    lastEmit = now
                                    _state.value = UpdateState.Downloading(release, read, total)
                                }
                            }
                            output.flush()
                        }
                    }
                    val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                    if (release.apkSha256 != null && !actualSha256.equals(release.apkSha256, ignoreCase = true)) {
                        partFile.delete()
                        throw IOException("Downloaded APK failed SHA-256 integrity verification.")
                    }
                    _state.value = UpdateState.Downloading(release, read, total)
                    if (!partFile.renameTo(targetFile)) {
                        partFile.copyTo(targetFile, overwrite = true)
                        partFile.delete()
                    }
                }
            }
            _state.value = UpdateState.ReadyToInstall(release, targetFile)
        } catch (e: CancellationException) {
            partFile.delete()
            _state.value = UpdateState.Idle
            throw e
        } catch (e: Exception) {
            partFile.delete()
            _state.value = UpdateState.Error(e.message ?: "Could not download update.", release)
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    suspend fun dismiss(rememberVersion: Boolean) {
        if (rememberVersion) {
            val version = when (val s = _state.value) {
                is UpdateState.Available -> s.release.version
                is UpdateState.ReadyToInstall -> s.release.version
                is UpdateState.Error -> s.release?.version
                else -> null
            }
            if (version != null) {
                settings?.setDismissedUpdateVersion(version)
            }
        }
        _state.value = UpdateState.Idle
    }

    fun canInstallPackages(): Boolean = context?.let(::canInstallPackages) ?: false

    fun openInstallPermissionSettings() {
        val ctx = context ?: return
        ctx.startActivity(unknownSourcesIntent(ctx))
    }

    fun installApk(apkFile: File) {
        val ctx = context ?: return
        installApk(ctx, apkFile)
    }

    companion object {
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        private val json = Json { ignoreUnknownKeys = true }

        private data class SemVer(
            val major: Int,
            val minor: Int,
            val patch: Int,
            val pre: List<String>,
        )

        fun normalizeVersion(raw: String): String =
            raw.trim().removePrefix("v").removePrefix("V").trim()

        private fun parseSemVer(raw: String): SemVer? {
            val cleaned = normalizeVersion(raw).substringBefore('+')
            if (cleaned.isEmpty()) return null
            val dash = cleaned.indexOf('-')
            val corePart = if (dash >= 0) cleaned.substring(0, dash) else cleaned
            val prePart = if (dash >= 0) cleaned.substring(dash + 1) else ""
            val nums = corePart.split('.')
            if (nums.isEmpty() || nums.size > 3) return null
            val major = nums[0].toIntOrNull() ?: return null
            val minor = nums.getOrNull(1)?.toIntOrNull() ?: if (nums.size > 1) return null else 0
            val patch = nums.getOrNull(2)?.toIntOrNull() ?: if (nums.size > 2) return null else 0
            if (major < 0 || minor < 0 || patch < 0) return null
            val pre = if (prePart.isEmpty()) emptyList() else prePart.split('.').filter { it.isNotEmpty() }
            return SemVer(major, minor, patch, pre)
        }

        fun compareSemVer(a: String, b: String): Int {
            val va = parseSemVer(a) ?: return normalizeVersion(a).compareTo(normalizeVersion(b))
            val vb = parseSemVer(b) ?: return normalizeVersion(a).compareTo(normalizeVersion(b))
            if (va.major != vb.major) return va.major.compareTo(vb.major)
            if (va.minor != vb.minor) return va.minor.compareTo(vb.minor)
            if (va.patch != vb.patch) return va.patch.compareTo(vb.patch)
            if (va.pre.isEmpty() && vb.pre.isEmpty()) return 0
            if (va.pre.isEmpty()) return 1
            if (vb.pre.isEmpty()) return -1
            val count = maxOf(va.pre.size, vb.pre.size)
            for (i in 0 until count) {
                val pa = va.pre.getOrNull(i) ?: return -1
                val pb = vb.pre.getOrNull(i) ?: return 1
                val na = pa.toIntOrNull()
                val nb = pb.toIntOrNull()
                val cmp = when {
                    na != null && nb != null -> na.compareTo(nb)
                    na != null -> -1
                    nb != null -> 1
                    else -> pa.compareTo(pb)
                }
                if (cmp != 0) return cmp
            }
            return 0
        }

        fun isNewer(remote: String, current: String): Boolean =
            compareSemVer(remote, current) > 0

        fun shouldAutoCheck(
            lastCheckMs: Long,
            nowMs: Long,
            enabled: Boolean = BuildConfig.ENABLE_SELF_UPDATE,
        ): Boolean = enabled && (lastCheckMs <= 0L || (nowMs - lastCheckMs) >= CHECK_INTERVAL_MS)

        fun parseLatestRelease(jsonBody: String, requireHttps: Boolean = true): UpdateRelease? {
            val root = runCatching { json.parseToJsonElement(jsonBody).jsonObject }.getOrNull() ?: return null
            if (root["draft"]?.jsonPrimitive?.booleanOrNull == true) return null
            if (root["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return null
            val tagName = root["tag_name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val version = normalizeVersion(tagName)
            if (parseSemVer(version) == null) return null
            val notes = root["body"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Bug fixes and improvements."
            val htmlUrl = root["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val assets = root["assets"]?.jsonArray.orEmpty()
            val apkAsset = assets.firstNotNullOfOrNull { element ->
                val obj = element as? JsonObject ?: return@firstNotNullOfOrNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val downloadUrl = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                val validScheme = downloadUrl != null && if (requireHttps) {
                    downloadUrl.startsWith("https://", ignoreCase = true)
                } else {
                    downloadUrl.startsWith("https://", ignoreCase = true) || downloadUrl.startsWith("http://", ignoreCase = true)
                }
                if (name.endsWith(".apk", ignoreCase = true) && validScheme) {
                    val digest = obj["digest"]?.jsonPrimitive?.contentOrNull
                        ?.removePrefix("sha256:")
                        ?.trim()
                        ?.lowercase()
                        ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
                    Triple(downloadUrl!!, obj["size"]?.jsonPrimitive?.longOrNull ?: -1L, digest)
                } else {
                    null
                }
            } ?: return null
            return UpdateRelease(
                version = version,
                tagName = tagName,
                notes = notes,
                htmlUrl = htmlUrl,
                apkUrl = apkAsset.first,
                apkSizeBytes = apkAsset.second,
                apkSha256 = apkAsset.third,
            )
        }

        private fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(16 * 1024)
            FileInputStream(file).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun canInstallPackages(context: Context): Boolean =
            context.packageManager.canRequestPackageInstalls()

        fun unknownSourcesIntent(context: Context): Intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun installApk(context: Context, apkFile: File) {
            val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", apkFile)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
