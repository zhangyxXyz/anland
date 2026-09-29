package com.anland.shell.ui

import android.content.Context
import android.content.ContextWrapper
import com.anland.design.update.ReleaseListResult
import com.anland.design.update.ReleaseResult
import com.anland.design.update.UpdateClient
import kotlinx.coroutines.runBlocking

/** Opt-in read-only integration probe against the public repository, without a GitHub token. */
object RepositoryProbe {
    @JvmStatic fun run(context: Context): String = runBlocking {
        for (pkg in listOf("com.anland.shell", "com.anlandnext")) {
            val client = UpdateClient(object : ContextWrapper(context) { override fun getPackageName() = pkg })
            val latest = client.latestRelease()
            check(latest is ReleaseResult.Success) { "Latest release for $pkg: $latest" }
            check(latest.release.apk?.name == client.config.assetName)
            val history = client.releases()
            check(history is ReleaseListResult.Success && history.releases.any { it.tag == latest.release.tag }) { "History for $pkg: $history" }
        }
        val client = UpdateClient(context)
        check(client.readme("en-US").getOrThrow().contains("Anland"))
        check(client.readme("zh-CN").getOrThrow().contains("容器"))
        check(client.repositoryLicense().getOrThrow().length > 1000)
        "PASS: public updates and history for both APKs, English/Chinese README, repository license (no authentication)\n"
    }
}
