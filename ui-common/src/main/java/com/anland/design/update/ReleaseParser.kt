package com.anland.design.update

import org.json.JSONArray
import org.json.JSONObject

/** The release tag versions the bundle; only component versions describe an APK. */
internal object ReleaseParser {
    fun objects(array: JSONArray?) = (0 until (array?.length() ?: 0)).map { array!!.getJSONObject(it) }
    fun eligible(release: JSONObject) = !release.optBoolean("draft", true) && !release.optBoolean("prerelease", true) &&
        release.optString("tag_name").matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+"))

    fun parse(release: JSONObject, manifest: JSONObject, config: UpdateServiceConfig): AppRelease? {
        if (!eligible(release)) return null
        val component = manifest.optJSONObject("components")?.optJSONObject(config.component) ?: return null
        val tag = release.getString("tag_name")
        require(manifest.getString("tag") == tag && manifest.optString("suffix").isEmpty()) { "Release manifest identity mismatch" }
        require(component.getString("component") == config.component) { "Wrong component manifest" }
        for (key in listOf("source", "run_id", "suffix")) {
            require(component.getString(key) == manifest.getString(key)) { "Component provenance mismatch" }
        }
        require(manifest.getJSONArray("selected").let { a -> (0 until a.length()).any { a.getString(it) == config.component } }) { "Unselected component" }
        val versions = manifest.getJSONObject("versions")
        require(tag == "v" + versions.getString("RELEASE_VERSION")) { "Bundle version/tag mismatch" }
        val componentVersions = component.getJSONObject("versions")
        val name = versions.getString("${config.versionPrefix}_VERSION_NAME")
        val code = versions.getString("${config.versionPrefix}_VERSION_CODE").toLong()
        require(code in 1..2_100_000_000L && name.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Invalid component version" }
        require(componentVersions.getString("${config.versionPrefix}_VERSION_NAME") == name &&
            componentVersions.getString("${config.versionPrefix}_VERSION_CODE").toLong() == code) { "Component version mismatch" }
        val asset = objects(release.optJSONArray("assets")).singleOrNull { it.optString("name") == config.assetName && it.optString("state") == "uploaded" } ?: return null
        val entry = objects(component.getJSONArray("files")).single { it.getString("name") == config.assetName }
        val hash = entry.getString("sha256")
        require(hash.matches(Regex("[0-9a-f]{64}")) && entry.getLong("size") == asset.getLong("size")) { "APK manifest mismatch" }
        val digest = asset.optString("digest")
        require(digest.isEmpty() || digest == "null" || digest == "sha256:$hash") { "APK digest mismatch" }
        require(asset.getLong("size") in 1..512L*1024*1024) { "Invalid APK size" }
        return AppRelease(tag, release.optString("name").ifBlank { tag }, release.optString("body"),
            release.getString("html_url"), release.optString("published_at"),
            ReleaseAsset(config.assetName, asset.getString("url"), asset.getString("browser_download_url"), asset.getLong("size"), hash), name, code)
    }
}
