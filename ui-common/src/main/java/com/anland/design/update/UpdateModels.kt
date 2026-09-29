package com.anland.design.update


data class UpdateServiceConfig(val packageName: String) {
    val component = when(packageName) {
        "com.anland.shell" -> "shell"
        "com.anlandnext" -> "wayland"
        else -> error("Unknown Anland application")
    }
    val versionPrefix = component.uppercase()
    val assetName = "anland-$component.apk"
    val providerName = "GitHub"
    val repositoryLabel = "zhangyxXyz/anland"
    val projectUrl = "https://github.com/$repositoryLabel"
    val upstreamRepositoryLabel = "SuperTurtleDev/anland"
    val upstreamUrl = "https://github.com/$upstreamRepositoryLabel"
    val releasesUrl = "$projectUrl/releases"
    val issuesUrl = "$projectUrl/issues"
    val contributorsUrl = "$projectUrl/graphs/contributors"
    val apiUrl = "https://api.github.com/repos/$repositoryLabel"
}

data class ReleaseAsset(val name: String, val apiUrl: String, val browserUrl: String, val size: Long, val sha256: String)
data class AppRelease(val tag: String, val name: String, val body: String, val pageUrl: String,
    val publishedAt: String, val apk: ReleaseAsset?, val versionName: String, val versionCode: Long,
    val prerelease: Boolean = false) {
    fun isNewerThan(name: String, code: Long) = versionCode > code ||
        (versionCode == code && name.contains("-dev.") && !versionName.contains("-dev."))
}
sealed interface ReleaseResult {
    data class Success(val release: AppRelease): ReleaseResult
    data object NoRelease: ReleaseResult
    data object AuthorizationRequired: ReleaseResult
    data class Failure(val message: String): ReleaseResult
}
sealed interface ReleaseListResult {
    data class Success(val releases: List<AppRelease>): ReleaseListResult
    data object AuthorizationRequired: ReleaseListResult
    data class Failure(val message: String): ReleaseListResult
}
