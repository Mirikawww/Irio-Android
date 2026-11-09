package com.irio

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class UpdateApkDownloaderTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun followsGatewayRedirectAndSavesOnlyVerifiedBytesWithProgress() = runTest {
        MockWebServer().use { server ->
            val data = ByteArray(700_000) { (it % 251).toByte() }
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/release-files/build.apk"))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(data)))
            val asset = UpdateAsset("universal", server.url("/gateway").toString(), sha(data), data.size.toLong(), "com.irio")
            val dir = kotlin.io.path.createTempDirectory("update-test").toFile()
            try {
                val progress = mutableListOf<UpdateDownloadProgress>()
                val file = UpdateApkDownloader(allowUrl = { it.host == "localhost" }).download(asset, dir, progress::add)
                assertArrayEquals(data, file.readBytes())
                assertEquals(0L, progress.first().bytes)
                assertEquals(data.size.toLong(), progress.last().bytes)
                assertTrue(progress.size >= 3)
                assertTrue("Publish progress before the old 256KiB threshold", progress.any { it.bytes in 1 until 262144 })
                assertFalse(dir.listFiles()!!.any { it.extension == "part" })
                assertNull(server.takeRequest().getHeader("Authorization"))
                assertEquals("/release-files/build.apk", server.takeRequest().path)
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun wrongDigestOrTruncatedFileNeverLeavesAnInstallableApk() = runTest {
        MockWebServer().use { server ->
            val dir = kotlin.io.path.createTempDirectory("update-failure").toFile()
            try {
                for (size in listOf(3L, 6L)) {
                    server.enqueue(MockResponse().setBody("bad"))
                    val asset = UpdateAsset("universal", server.url("/build.apk").toString(), "0".repeat(64), size, "com.irio")
                    try {
                        UpdateApkDownloader(allowUrl = { it.host == "localhost" }).download(asset, dir) {}
                        fail("Must reject corrupt download")
                    } catch (_: IOException) { }
                    assertTrue(dir.listFiles().orEmpty().isEmpty())
                }
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun gatewayRedirectCannotEscapeTheTrustedHosts() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.test/build.apk"))
            val dir = kotlin.io.path.createTempDirectory("update-redirect").toFile()
            try {
                try {
                    UpdateApkDownloader(allowUrl = { it.host == "localhost" }).download(
                        UpdateAsset("universal", server.url("/gateway").toString(), "0".repeat(64), 3, "com.irio"), dir) {}
                    fail("Must reject a foreign host")
                } catch (_: IOException) { }
                assertEquals(1, server.requestCount)
            } finally { dir.deleteRecursively() }
        }
    }
}
