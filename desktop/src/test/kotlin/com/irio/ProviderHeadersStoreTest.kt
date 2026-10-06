package com.irio

import java.io.File
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.Assert.*

class ProviderHeadersStoreTest {
    @Before fun reset() {
        check(File(requireNotNull(System.getenv("IRIO_HOME"))).canonicalFile == AppPaths.root.canonicalFile)
        AppPaths.providersFile.delete()
        AppPaths.keyFile.delete()
    }
    @After fun clean() {
        AppPaths.providersFile.delete()
        AppPaths.keyFile.delete()
    }

    @Test fun headersAreEncryptedAtRestAndSurviveSaveAndSnapshotRoundTrip() {
        val headers = listOf(ProviderHeader("x-goog-api-key", "private-fixture-value"))
        val config = ProviderConfig("p", "Fixture", "https://example.invalid/v1", "", customHeaders = headers)
        ProviderStore.saveProvider(config)
        assertEquals(headers, ProviderStore.loadProvider("p")!!.customHeaders)
        assertFalse(AppPaths.providersFile.readText().contains("private-fixture-value"))
        val snapshot = ProviderStore.snapshot()
        assertEquals("private-fixture-value", snapshot.getJSONArray("providers").getJSONObject(0)
            .getJSONArray("customHeaders").getJSONObject(0).getString("value"))
        ProviderStore.applySnapshot(snapshot, overwrite = true)
        assertEquals(headers, ProviderStore.loadProvider("p")!!.customHeaders)
        ProviderStore.saveProviderEnabled("p", false)
        ProviderStore.saveDisabledModels("p", emptySet())
        assertEquals(headers, ProviderStore.loadProvider("p")!!.customHeaders)
        ProviderStore.saveProvider(ProviderStore.loadProvider("p")!!.copy(customHeaders = emptyList()))
        assertEquals(emptyList<ProviderHeader>(), ProviderStore.loadProvider("p")!!.customHeaders)
    }
}
