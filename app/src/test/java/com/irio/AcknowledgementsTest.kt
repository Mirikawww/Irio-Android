package com.irio

import android.content.ComponentName
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AcknowledgementsTest {
    @Test fun independentProjectsHaveSeparateCreditsAndTheirOwnUrls() {
        val expected = mapOf(
            "okhttp" to ("OkHttp" to "https://github.com/square/okhttp"),
            "okio" to ("Okio" to "https://github.com/square/okio"),
            "kotlin" to ("Kotlin" to "https://github.com/JetBrains/kotlin"),
            "coroutines" to ("kotlinx.coroutines" to "https://github.com/Kotlin/kotlinx.coroutines"),
            "compose" to ("Jetpack Compose" to "https://developer.android.com/jetpack/compose"),
            "recyclerview" to ("RecyclerView" to "https://developer.android.com/jetpack/androidx/releases/recyclerview"),
            "proot" to ("PRoot" to "https://github.com/termux/proot"),
            "talloc" to ("talloc" to "https://talloc.samba.org"),
            "android-shmem" to ("libandroid-shmem" to "https://github.com/termux/libandroid-shmem"),
            "alpine" to ("Alpine Linux" to "https://alpinelinux.org"),
        )
        for ((id, project) in expected) {
            val credit = acknowledgements.singleOrNull { it.id == id }
            assertNotNull("$id must have a separate acknowledgement, not be combined with another library", credit)
            assertEquals(project.first, credit!!.name)
            assertEquals(project.second, credit.projectUrl)
        }
        assertTrue("Project titles must not combine independent libraries", acknowledgements.none { it.name.contains(" / ") })
    }

    @Test fun everyCreditHasARealLocalLicenseAndHttpsProjectAddress() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(acknowledgements.size, acknowledgements.map { it.id }.toSet().size)
        for (credit in acknowledgements) {
            assertTrue(credit.name, credit.projectUrl.startsWith("https://"))
            assertTrue(credit.name, credit.licenses.isNotEmpty())
            for (license in credit.licenses) {
                val text = context.assets.open(license.assetPath).bufferedReader().use { it.readText() }
                assertTrue("${credit.name}: ${license.assetPath} must be shipped offline", text.length > 300)
                assertFalse("${license.assetPath} must not be an HTML download error", text.trimStart().startsWith("<!DOCTYPE html", true))
            }
        }
    }

    @Test fun loraAndRealLocalModificationsAreAcknowledgedWithoutMislabelingDependencies() {
        val lora = acknowledgements.single { it.id == "lora" }
        assertEquals("Lora", lora.name)
        assertNull("The font files themselves were not modified", lora.adaptation)
        val license = RuntimeEnvironment.getApplication().assets.open(lora.licenses.single().assetPath)
            .bufferedReader().use { it.readText() }
        assertTrue(license.contains("The Lora Project Authors"))
        assertTrue(license.contains("SIL OPEN FONT LICENSE"))
        val modified = acknowledgements.single { it.adaptation == "本地修改" }
        assertEquals("markdown", modified.id)
        assertTrue(modified.modification!!.contains("原始行号"))
        assertEquals("本地适配", acknowledgements.single { it.id == "lucide" }.adaptation)
        for (id in listOf("proot", "talloc", "android-shmem")) {
            assertEquals(id, "打包适配", acknowledgements.single { it.id == id }.adaptation)
        }
        assertNull("Alpine is bundled unchanged", acknowledgements.single { it.id == "alpine" }.adaptation)
        assertNull(acknowledgements.single { it.id == "mermaid" }.adaptation)
        assertNull(acknowledgements.single { it.id == "jlatexmath" }.adaptation)
    }

    @Test fun aboutAndAcknowledgementsActivitiesAreRegisteredAndPrivate() {
        val context = RuntimeEnvironment.getApplication()
        for (activity in listOf(AboutActivity::class.java, AcknowledgementsActivity::class.java)) {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getActivityInfo(ComponentName(context, activity), 0)
            assertFalse(activity.name, info.exported)
        }
    }
}
