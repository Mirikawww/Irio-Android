package com.irio

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class ReleaseUpdatesTest {
    private val digest = "a".repeat(64)
    private fun release(version: String, code: Int, channel: String, abi: String = "universal") =
        """{"versionName":"$version","versionCode":$code,"channel":"$channel","notes":"## Notes","assets":[{"abi":"$abi","url":"https://irio.relithy.space/releases/download/fixture?via=cdn","sha256":"$digest","size":123,"packageName":"com.irio"}]}"""

    @Test fun websiteFeedSelectsChannelsAndAnAbiCompatibleRealAsset() {
        val feed = """{"schemaVersion":1,"releases":[${release("1.2.0-gamma", 11, "gamma")},${release("1.1.0-iota", 10, "iota")}]}"""
        val stable = parseReleaseUpdates(feed, listOf("arm64-v8a"), UpdateChannel.RELEASE).single()
        assertEquals("1.1.0-iota", stable.tag)
        assertEquals(IRIO_RELEASES_URL, stable.url)
        assertEquals("com.irio", stable.asset!!.packageName)
        assertEquals(digest, stable.asset.sha256)
        assertEquals(10, stable.versionCode)
        assertEquals("1.2.0-gamma", parseReleaseUpdates(feed, listOf("arm64-v8a"), UpdateChannel.PRE_RELEASE).first().tag)
    }

    @Test fun malformedDigestUnknownPackageAndForeignDownloadHostsFailClosed() {
        val valid = """{"schemaVersion":1,"releases":[${release("1.1.0-iota", 10, "iota")}]}"""
        for (bad in listOf(valid.replace(digest, "bad"), valid.replace("com.irio", "com.other"),
            valid.replace("https://irio.relithy.space/releases/download/fixture", "https://evil.test/payload.apk"))) {
            assertThrows(UpdateCheckException::class.java) { parseReleaseUpdates(bad, listOf("arm64-v8a"), UpdateChannel.RELEASE) }
        }
    }

    @Test fun clientUsesTheWebsiteEndpointAndDebugPreviewDoesNotLieAboutVersionOrdering() = runTest {
        MockWebServer().use { server ->
            val feed = """{"schemaVersion":1,"releases":[${release("1.1.0-iota", 10, "iota")}]}"""
            server.enqueue(MockResponse().setBody(feed))
            server.enqueue(MockResponse().setBody(feed))
            val client = ReleaseUpdateClient(endpoint = server.url("/releases/api/android-updates"), supportedAbis = listOf("arm64-v8a"))
            assertEquals(UpdateCheckResult.UpToDate, client.check("1.3.0-gamma-debug", UpdateChannel.RELEASE, 12))
            val preview = client.preview(UpdateChannel.RELEASE)!!
            assertTrue(preview.testDownload)
            assertEquals("1.1.0-iota", preview.tag)
            val request = server.takeRequest()
            assertEquals("/releases/api/android-updates", request.path)
            assertNull(request.getHeader("Authorization"))
        }
    }
}
