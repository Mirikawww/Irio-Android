package com.irio

import android.content.Context
import android.content.pm.PackageManager

internal fun softwareInfoLine(
    platform: String,
    versionName: String,
    versionCode: Long,
    buildType: String,
): String {
    val name = if (buildType == "debug") versionName.removeSuffix("-debug") else versionName
    return "Irio, $platform, $name, $versionCode, $buildType"
}

internal fun softwareBuildType(versionName: String): String =
    if (versionName.endsWith("-debug")) "debug" else "release"

internal fun androidSoftwareInfo(context: Context): String {
    val info = context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.PackageInfoFlags.of(0),
    )
    val rawName = info.versionName.orEmpty()
    val buildType = softwareBuildType(rawName)
    return softwareInfoLine(
        platform = "Android",
        versionName = rawName,
        versionCode = info.longVersionCode,
        buildType = buildType,
    )
}
