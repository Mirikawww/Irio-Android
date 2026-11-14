package com.irio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UserLocationPermissionSessionTest {
    @Test fun returningFromAnotherHostRestoresTheResumedChatPermissionSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var mainForeground = true
        var mainLaunches = 0
        var childLaunches = 0
        val main = UserLocationPermissionSession({ false }, { mainForeground }, { mainLaunches++ })
        val child = UserLocationPermissionSession({ false }, { !mainForeground }, { childLaunches++ })
        try {
            UserLocationPermissions.attach(main)
            UserLocationPermissions.attach(child)
            val first = async { UserLocationPermissions.request() }
            testScheduler.runCurrent()
            assertEquals(1, mainLaunches)
            assertEquals(0, childLaunches)
            main.complete()
            assertFalse(first.await())
            mainForeground = false
            val second = async { UserLocationPermissions.request() }
            testScheduler.runCurrent()
            assertEquals(1, childLaunches)
            child.complete()
            assertFalse(second.await())
            UserLocationPermissions.detach(child)
            mainForeground = true
            val third = async { UserLocationPermissions.request() }
            testScheduler.runCurrent()
            assertEquals(2, mainLaunches)
            main.complete()
            assertFalse(third.await())
        } finally {
            UserLocationPermissions.detach(child)
            UserLocationPermissions.detach(main)
            Dispatchers.resetMain()
        }
    }

    @Test fun permissionIsRequestedOnlyOnCallAndConcurrentCallsShareDialog() = runTest {
        var granted = false
        var launches = 0
        val session = UserLocationPermissionSession({ granted }, { true }, { launches++ })
        assertEquals(0, launches)
        val first = async { session.request() }
        val second = async { session.request() }
        testScheduler.runCurrent()
        assertEquals(1, launches)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        granted = true
        session.complete()
        assertTrue(first.await())
        assertTrue(second.await())
        assertTrue(session.request())
        assertEquals(1, launches)
    }

    @Test fun denyingPermissionReturnsFalseAndLaterCallCanRequestAgain() = runTest {
        var launches = 0
        val session = UserLocationPermissionSession({ false }, { true }, { launches++ })
        val first = async { session.request() }
        testScheduler.runCurrent()
        session.complete()
        assertFalse(first.await())
        val second = async { session.request() }
        testScheduler.runCurrent()
        assertEquals(2, launches)
        session.complete()
        assertFalse(second.await())
    }

    @Test fun cancellingOneCallerDoesNotCancelOtherCallerOrRelaunchDialog() = runTest {
        var granted = false
        var launches = 0
        val session = UserLocationPermissionSession({ granted }, { true }, { launches++ })
        val first = async { session.request() }
        testScheduler.runCurrent()
        first.cancel()
        val second = async { session.request() }
        testScheduler.runCurrent()
        assertEquals(1, launches)
        granted = true
        session.complete()
        assertTrue(second.await())
    }

    @Test fun noForegroundHostCannotLaunchAndDisposalReleasesWaiters() = runTest {
        var foreground = false
        var launches = 0
        val session = UserLocationPermissionSession({ false }, { foreground }, { launches++ })
        assertFalse(session.request())
        assertEquals(0, launches)
        foreground = true
        val request = async { session.request() }
        testScheduler.runCurrent()
        session.close()
        assertFalse(request.await())
        assertFalse(session.request())
        assertEquals(1, launches)
    }
}
