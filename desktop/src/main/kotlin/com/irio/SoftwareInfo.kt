package com.irio

internal fun softwareInfoLine(
    platform: String,
    versionName: String,
    versionCode: Long,
    buildType: String,
): String {
    val name = if (buildType == "debug") versionName.removeSuffix("-debug") else versionName
    return "Irio, $platform, $name, $versionCode, $buildType"
}
