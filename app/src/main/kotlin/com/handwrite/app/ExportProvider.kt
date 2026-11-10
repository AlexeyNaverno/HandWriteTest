package com.handwrite.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Minimal content provider that serves the exported PDFs as `content://` URIs.
 *
 * Sharing a `file://` URI throws FileUriExposedException on Android 7+, and pulling in AndroidX
 * just for FileProvider would add ~1 MB to the APK, so the app ships its own 60-line provider.
 */
class ExportProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.handwrite.app.files"

        fun uriFor(file: File): Uri = Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(file.name)
            .build()

        fun dir(context: Context): File {
            val d = File(context.filesDir, "exports")
            if (!d.exists()) d.mkdirs()
            return d
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val file = resolve(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols, 1)
        val row = arrayOfNulls<Any>(cols.size)
        for ((i, c) in cols.withIndex()) {
            row[i] = when (c) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    override fun getType(uri: Uri): String = "application/pdf"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = resolve(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun resolve(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.contains("..")) return null
        val ctx = context ?: return null
        val f = File(dir(ctx), name)
        return if (f.exists()) f else null
    }
}
