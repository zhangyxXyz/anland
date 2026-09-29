package com.anland.design.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class UpdateClient(context: Context, private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()) {
    val config = UpdateServiceConfig(context.packageName)

    suspend fun latestRelease(): ReleaseResult = when(val result = releases()) {
        is ReleaseListResult.Success -> result.releases.maxWithOrNull(compareBy<AppRelease> { it.versionCode }.thenBy { it.publishedAt })
            ?.let(ReleaseResult::Success) ?: ReleaseResult.NoRelease
        ReleaseListResult.AuthorizationRequired -> ReleaseResult.AuthorizationRequired
        is ReleaseListResult.Failure -> ReleaseResult.Failure(result.message)
    }

    suspend fun releases(): ReleaseListResult = withContext(Dispatchers.IO) {
        try {
            val releases = mutableListOf<AppRelease>()
            for (page in 1..10) {
                coroutineContext.ensureActive()
                val array = JSONArray(get("${config.apiUrl}/releases?per_page=100&page=$page", 8*1024*1024))
                for (entry in ReleaseParser.objects(array).filter(ReleaseParser::eligible)) {
                    val asset = ReleaseParser.objects(entry.optJSONArray("assets"))
                        .singleOrNull { it.optString("name") == "build-manifest.json" && it.optString("state") == "uploaded" } ?: continue
                    // Missing manifests describe older releases. A malformed present manifest is an error,
                    // never a reason to silently claim an older APK is the newest available version.
                    val data = get(asset.getString("browser_download_url"), 2*1024*1024)
                    val digest = asset.optString("digest")
                    if (digest.startsWith("sha256:")) require("sha256:"+sha256(data.toByteArray()) == digest) { "Release manifest checksum mismatch" }
                    ReleaseParser.parse(entry, JSONObject(data), config)?.let(releases::add)
                }
                if (array.length() < 100) return@withContext ReleaseListResult.Success(releases.sortedWith(compareByDescending<AppRelease> { it.versionCode }.thenByDescending { it.publishedAt }))
            }
            ReleaseListResult.Failure("Release history is too large; open GitHub Releases to inspect it")
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) { ReleaseListResult.Failure(e.message ?: "Update check failed") }
    }

    suspend fun download(asset: ReleaseAsset, target: File, progress: suspend (Long, Long) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        val temporary = File(target.parentFile, target.name+".part")
        try {
            require(asset.name == config.assetName) { "Wrong application asset" }
            target.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            http.newCall(request(asset.browserUrl, "application/octet-stream")).execute().use { response ->
                check(response.isSuccessful) { "Download HTTP ${response.code}" }
                val body = response.body ?: error("Empty APK response")
                progress(0, asset.size)
                body.byteStream().use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(64*1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        copied += count
                        require(copied <= asset.size) { "APK exceeds declared size" }
                        output.write(buffer, 0, count); digest.update(buffer, 0, count)
                        progress(copied, asset.size)
                    }
                } }
            }
            require(copied == asset.size && hex(digest.digest()) == asset.sha256) { "APK checksum/size mismatch" }
            check(!target.exists() || target.delete())
            check(temporary.renameTo(target)) { "Cannot save downloaded APK" }
            Result.success(target)
        } catch(e: CancellationException) { temporary.delete(); throw e }
          catch(e: Exception) { temporary.delete(); Result.failure(e) }
    }

    private fun get(url: String, limit: Int, accept: String = "application/vnd.github+json"): String =
        http.newCall(request(url, accept)).execute().use { response ->
            check(response.isSuccessful) {
                if (response.code == 403 || response.code == 429) "GitHub rate limit or access restriction; try later"
                else "GitHub HTTP ${response.code}"
            }
            val body = response.body ?: error("Empty GitHub response")
            body.byteStream().use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while(true) { val count=input.read(buffer); if(count<0)break
                    require(bytes.size()+count <= limit) { "Response is too large" }; bytes.write(buffer,0,count) }
                bytes.toString("UTF-8")
            }
        }
    private fun request(url: String, accept: String): Request {
        require(url.startsWith("https://")) { "Updates require HTTPS" }
        return Request.Builder().url(url).header("Accept",accept).header("X-GitHub-Api-Version","2022-11-28").build()
    }
    companion object {
        internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        internal fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
