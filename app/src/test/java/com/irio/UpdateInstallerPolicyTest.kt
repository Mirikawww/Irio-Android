package com.irio

import org.junit.Assert.*
import org.junit.Test

class UpdateInstallerPolicyTest {
    @Test fun rejectsWrongPackageVersionAndPublisherBeforeHandingOffToInstaller() {
        val release = GitHubUpdate("1.2.0-gamma", IRIO_RELEASES_URL, true, versionCode = 11)
        val archive = UpdatePackageIdentity("com.irio", 11, setOf("publisher"))
        assertNull(updateInstallProblem(release, archive, null))
        assertNotNull(updateInstallProblem(release, archive.copy(packageName = "other"), null))
        assertNotNull(updateInstallProblem(release, archive.copy(versionCode = 12), null))
        assertNotNull(updateInstallProblem(release, archive, archive.copy(signers = setOf("other"))))
        assertNotNull(updateInstallProblem(release, archive, archive.copy(versionCode = 12)))
        assertNull(updateInstallProblem(release, archive, archive.copy(versionCode = 10)))
    }
}
