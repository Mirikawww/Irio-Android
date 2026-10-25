package com.irio

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupUpdateCheckTest {
    @Test fun automaticChecksStaySilentWhenUpToDateOrThereAreNoReleases() = runTest {
        for (result in listOf(UpdateCheckResult.UpToDate, UpdateCheckResult.NoReleases)) {
            val check = StartupUpdateCheck(this)
            var requests = 0
            check.start { requests++; result }
            runCurrent()
            assertNull("Automatic checks without a new release must not queue a snackbar: $result", check.notice.value)
            assertFalse("A silent result must still count as this startup's one check", check.start { requests++; result })
            assertEquals(1, requests)
        }
    }

    @Test fun repeatedActivityStartsOnlyRunOneCheckEvenWhileTheFirstIsPending() = runTest {
        val check = StartupUpdateCheck(this)
        val result = CompletableDeferred<UpdateCheckResult>()
        var requests = 0
        assertTrue(check.start { requests++; result.await() })
        repeat(10) { assertFalse(check.start { requests++; UpdateCheckResult.UpToDate }) }
        runCurrent()
        assertEquals(1, requests)
        assertNull(check.notice.value)
        result.complete(UpdateCheckResult.NoReleases)
        runCurrent()
        assertNull(check.notice.value)
        assertFalse(check.start { requests++; UpdateCheckResult.UpToDate })
        assertEquals(1, requests)
    }

    @Test fun aNewProcessGateChecksAgainButDismissalDoesNotResetTheOldGate() = runTest {
        var requests = 0
        val first = StartupUpdateCheck(this)
        first.start { requests++; UpdateCheckResult.Available(GitHubUpdate("1.0.0", "$IRIO_PROJECT_URL/releases/tag/1.0.0", false)) }
        runCurrent()
        first.dismiss(requireNotNull(first.notice.value))
        assertNull(first.notice.value)
        assertFalse(first.start { requests++; UpdateCheckResult.UpToDate })
        val next = StartupUpdateCheck(this)
        assertTrue(next.start { requests++; UpdateCheckResult.UpToDate })
        runCurrent()
        assertEquals(2, requests)
    }

    @Test fun updateAndNetworkErrorsProduceOnePendingNoticeWithoutRetrying() = runTest {
        for ((error, key) in listOf(
            UpdateCheckException("GitHub 请求过于频繁，请稍后重试") to "GitHub 请求过于频繁，请稍后重试",
            IOException("private transport details") to "检查更新失败，请检查网络后重试",
        )) {
            val check = StartupUpdateCheck(this)
            check.start { throw error }
            runCurrent()
            assertEquals(StartupUpdateNotice.Failed(key), check.notice.value)
            assertFalse(check.start { UpdateCheckResult.UpToDate })
        }
    }

    @Test fun cancellationIsNotReportedAsAnUpdateFailure() = runTest {
        val check = StartupUpdateCheck(this)
        check.start { throw CancellationException("ending process") }
        runCurrent()
        assertNull(check.notice.value)
    }

    @Test fun allResultsHaveLocalizedSnackbarTextAndNewVersionsKeepTheirTag() {
        val release = GitHubUpdate("1.0.0-beta.3", "$IRIO_PROJECT_URL/releases/tag/1.0.0-beta.3", true)
        val available = StartupUpdateNotice.Checked(UpdateCheckResult.Available(release))
        assertEquals("发现新版本：1.0.0-beta.3", startupUpdateMessage(available) { it })
        assertEquals("Update 1.0.0-beta.3 is available",
            startupUpdateMessage(available) { translate(AppLanguage.EN, it) })
        assertEquals("GitHub 请求过于频繁，请稍后重试",
            startupUpdateMessage(StartupUpdateNotice.Failed("GitHub 请求过于频繁，请稍后重试")) { it })
    }
}
