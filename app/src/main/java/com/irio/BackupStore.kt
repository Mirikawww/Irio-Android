package com.irio

import android.content.Context
import java.io.InputStream
import java.io.OutputStream

data class BackupImportResult(
    val success: Boolean,
    val message: String,
    val conversationCount: Int = 0,
    val providerRestored: Boolean = false,
    val sttRestored: Boolean = false,
    val searchRestored: Boolean = false,
    val missing: List<String> = emptyList(),
)

object BackupFileValidation {
    fun isIrioFile(name: String): Boolean = name.lowercase().endsWith(".irio")
}

/** Exports application data, but deliberately never includes attachment bytes. */
object BackupStore {
    fun export(context: Context, output: OutputStream) = BackupArchive.export(context, output)

    fun import(context: Context, input: InputStream, overwrite: Boolean = true): BackupImportResult =
        BackupArchive.import(context, input, overwrite)
}
