package com.irio

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit delivery probe only; ordinary test runs stay offline. */
class LiveWebsiteUpdateTest {
    @Test fun realWebsiteFeedAndApplicationDownloaderProduceAVerifiedApk() = runTest {
        assumeTrue(System.getenv("IRIO_VERIFY_WEBSITE_UPDATE") == "1")
        val release = ReleaseUpdateClient(supportedAbis = listOf("arm64-v8a")).preview(UpdateChannel.PRE_RELEASE)
        assertNotNull("The live public catalog must contain an installable release", release)
        val asset = release!!.asset!!
        val directory = File("build/tmp/live-website-update").apply { mkdirs() }
        val states = mutableListOf<UpdateDownloadProgress>()
        val file = UpdateApkDownloader().download(asset, directory, states::add)
        assertEquals(asset.size, file.length())
        assertEquals(asset.size, states.last().bytes)
        assertTrue(states.size > 2)
        println("Verified real Kotlin download: ${release.tag} (${release.versionCode}), ${asset.size} bytes, SHA-256 ${asset.sha256}, ${asset.url}, ${file.absolutePath}")
    }
}
