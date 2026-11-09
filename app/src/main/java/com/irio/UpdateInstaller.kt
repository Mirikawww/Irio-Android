package com.irio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal data class UpdatePackageIdentity(val packageName: String, val versionCode: Long, val signers: Set<String>)

internal fun updateInstallProblem(release: GitHubUpdate, archive: UpdatePackageIdentity, installed: UpdatePackageIdentity?): String? = when {
    archive.packageName != "com.irio" -> "更新安装包的包名不匹配"
    archive.versionCode != release.versionCode.toLong() -> "更新安装包的版本不匹配"
    archive.signers.isEmpty() -> "更新安装包没有有效签名"
    installed != null && archive.signers != installed.signers -> "更新安装包的签名与已安装版本不一致"
    installed != null && archive.versionCode < installed.versionCode -> "下载的版本低于已安装版本，无法降级安装"
    else -> null
}

private fun PackageInfo.updateIdentity(): UpdatePackageIdentity = UpdatePackageIdentity(
    packageName, longVersionCode, signingInfo?.apkContentsSigners.orEmpty().map {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
    }.toSet(),
)

internal fun validateDownloadedUpdate(context: Context, release: GitHubUpdate, file: File) {
    val pm = context.packageManager
    val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
    val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)?.updateIdentity()
        ?: throw IOException("无法读取更新安装包")
    val installed = try { pm.getPackageInfo("com.irio", flags).updateIdentity() }
        catch (_: PackageManager.NameNotFoundException) { null }
    updateInstallProblem(release, archive, installed)?.let { throw IOException(it) }
}

internal fun updateInstallerIntent(
    context: Context,
    file: File,
    uriForFile: (Context, File) -> Uri = { owner, apk -> FileProvider.getUriForFile(owner, "${owner.packageName}.fileprovider", apk) },
): Intent {
    val uri = uriForFile(context, file)
    require(uri.scheme == "content")
    return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Return false while awaiting the user's per-app installation permission. */
internal fun launchUpdateInstaller(
    context: Context,
    file: File,
    installerIntent: (Context, File) -> Intent = { owner, apk -> updateInstallerIntent(owner, apk) },
): Boolean {
    if (!context.packageManager.canRequestPackageInstalls()) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return false
    }
    context.startActivity(installerIntent(context, file))
    return true
}
