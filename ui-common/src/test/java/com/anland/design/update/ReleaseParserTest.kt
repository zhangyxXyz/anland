package com.anland.design.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseParserTest {
    private fun fixture(component: String="shell", code: String="7", name: String="0.3.0"): Pair<JSONObject,JSONObject> {
        val config=UpdateServiceConfig(if(component=="shell") "com.anland.shell" else "com.anlandnext")
        val versions=JSONObject().put("RELEASE_VERSION","0.5.0").put("${config.versionPrefix}_VERSION_NAME",name).put("${config.versionPrefix}_VERSION_CODE",code)
        val file=JSONObject().put("name",config.assetName).put("size",100).put("sha256","a".repeat(64))
        val part=JSONObject().put("component",component).put("source","b".repeat(40)).put("run_id","123").put("suffix","").put("versions",versions).put("files",JSONArray().put(file))
        val manifest=JSONObject().put("tag","v0.5.0").put("source","b".repeat(40)).put("run_id","123").put("suffix","")
            .put("selected",JSONArray().put(component)).put("versions",versions).put("components",JSONObject().put(component,part))
        val asset=JSONObject().put("name",config.assetName).put("state","uploaded").put("size",100).put("digest","sha256:"+"a".repeat(64))
            .put("url","https://api.github.com/assets/1").put("browser_download_url","https://github.com/download/app.apk")
        val release=JSONObject().put("tag_name","v0.5.0").put("draft",false).put("prerelease",false)
            .put("html_url","https://github.com/releases/v0.5.0").put("assets",JSONArray().put(asset))
        return release to manifest
    }
    @Test fun `component version is independent from bundle tag`() {
        val (release,manifest)=fixture()
        val result=ReleaseParser.parse(release,manifest,UpdateServiceConfig("com.anland.shell"))!!
        assertEquals("0.3.0",result.versionName); assertEquals(7L,result.versionCode)
        assertTrue(result.isNewerThan("0.4.0",6));assertFalse(result.isNewerThan("0.1.0",8))
    }
    @Test fun `each app only finds its own main APK`() {
        for(component in listOf("shell","wayland")) {
            val (release,manifest)=fixture(component)
            val own=UpdateServiceConfig(if(component=="shell") "com.anland.shell" else "com.anlandnext")
            val other=UpdateServiceConfig(if(component=="shell") "com.anlandnext" else "com.anland.shell")
            assertEquals(own.assetName,ReleaseParser.parse(release,manifest,own)!!.apk!!.name)
            assertNull(ReleaseParser.parse(release,manifest,other))
            release.getJSONArray("assets").getJSONObject(0).put("name","anland-$component-tests.apk")
            assertNull(ReleaseParser.parse(release,manifest,own))
        }
    }
    @Test fun `draft prerelease and manual development runs are excluded`() {
        val (release,manifest)=fixture();val config=UpdateServiceConfig("com.anland.shell")
        release.put("draft",true);assertNull(ReleaseParser.parse(release,manifest,config))
        release.put("draft",false).put("prerelease",true);assertNull(ReleaseParser.parse(release,manifest,config))
        release.put("prerelease",false).put("tag_name","dev-abcdef01-123");assertNull(ReleaseParser.parse(release,manifest,config))
    }
    @Test fun `checksum or source mismatch fails closed`() {
        for(field in listOf("digest","source")) {
            val (release,manifest)=fixture()
            if(field=="digest")release.getJSONArray("assets").getJSONObject(0).put("digest","sha256:"+"f".repeat(64))
            else manifest.getJSONObject("components").getJSONObject("shell").put("source","c".repeat(40))
            assertThrows(IllegalArgumentException::class.java) { ReleaseParser.parse(release,manifest,UpdateServiceConfig("com.anland.shell")) }
        }
    }
    @Test fun `same code stable APK can replace a development APK but never downgrades`() {
        val (release,manifest)=fixture();val result=ReleaseParser.parse(release,manifest,UpdateServiceConfig("com.anland.shell"))!!
        assertTrue(result.isNewerThan("0.3.0-dev.2+abcdef01",7));assertFalse(result.isNewerThan("0.3.0",7))
        assertFalse(result.isNewerThan("0.3.0-dev.2+abcdef01",8))
    }
    @Test fun `published component assets work before a bundle manifest exists`() {
        for (kind in listOf("shell", "wayland")) {
            val (release, manifest) = fixture(kind)
            val config = UpdateServiceConfig(if (kind == "shell") "com.anland.shell" else "com.anlandnext")
            val part = manifest.getJSONObject("components").getJSONObject(kind)
            release.put("target_commitish", part.getString("source"))
            val result = ReleaseParser.parseComponent(release, part, config)!!
            assertEquals(7L, result.versionCode)
            assertEquals(config.assetName, result.apk!!.name)
            release.put("target_commitish", "c".repeat(40))
            assertThrows(IllegalArgumentException::class.java) { ReleaseParser.parseComponent(release, part, config) }
        }
    }
    @Test fun `component fallback rejects development builds and altered hashes`() {
        val (release, manifest) = fixture()
        val part = manifest.getJSONObject("components").getJSONObject("shell")
        val config = UpdateServiceConfig("com.anland.shell")
        part.put("suffix", "-dev.1+12345678")
        assertThrows(IllegalArgumentException::class.java) { ReleaseParser.parseComponent(release, part, config) }
        part.put("suffix", "")
        part.getJSONArray("files").getJSONObject(0).put("sha256", "f".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { ReleaseParser.parseComponent(release, part, config) }
    }
    @Test fun `release notes remain visible without an installable APK`() {
        val (release, _) = fixture()
        release.put("body", "Release notes")
        val item = ReleaseParser.historyOnly(release)
        assertEquals("Release notes", item.body)
        assertNull(item.apk)
        assertFalse(item.isNewerThan("0.2.2", 4))
    }
}
