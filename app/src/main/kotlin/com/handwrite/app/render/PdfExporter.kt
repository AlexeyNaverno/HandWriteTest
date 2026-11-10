package com.handwrite.app.render

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves the generated PDF into the app folder and hands it to the system (share / open). */
object PdfExporter {

    fun exportDir(context: Context): File = com.handwrite.app.ExportProvider.dir(context)

    fun save(context: Context, bytes: ByteArray, styleName: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safe = styleName.replace(Regex("[^A-Za-zА-Яа-я0-9]+"), "_").trim('_').ifEmpty { "handwrite" }
        val f = File(exportDir(context), "handwrite-$safe-$stamp.pdf")
        f.writeBytes(bytes)
        return f
    }

    fun shareIntent(file: File): Intent {
        val uri = com.handwrite.app.ExportProvider.uriFor(file)
        val i = Intent(Intent.ACTION_SEND)
        i.type = "application/pdf"
        i.putExtra(Intent.EXTRA_STREAM, uri)
        i.putExtra(Intent.EXTRA_SUBJECT, file.name)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = android.content.ClipData.newRawUri(file.name, uri)
        return i
    }

    fun openIntent(file: File): Intent {
        val uri = com.handwrite.app.ExportProvider.uriFor(file)
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "application/pdf")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return i
    }

    /**
     * Fallback for devices without a PDF viewer: hand the file to the system print service,
     * which offers "Save as PDF" as well.
     */
    fun print(context: Context, file: File, title: String) {
        val pm = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val web = WebView(context)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                try {
                    pm.print(title, view.createPrintDocumentAdapter(title),
                        PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize("a5", "A5", 14800, 21000))
                            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                            .build())
                } catch (e: Exception) {
                    Toast.makeText(context, "Печать недоступна", Toast.LENGTH_LONG).show()
                }
            }
        }
        web.loadUrl("content://${com.handwrite.app.ExportProvider.AUTHORITY}/${file.name}")
    }
}
