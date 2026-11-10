package com.handwrite.app.handwriting

import android.content.Context
import com.handwrite.core.handwriting.HandwriteModel as CoreModel

/**
 * Android entry point for the on-device glyph network: loads the quantised CNN from assets once
 * and keeps it in memory. All the maths lives in [CoreModel] (`core` module) so it can be unit
 * tested on the JVM and shared with the training tools.
 */
object HandwriteModelLoader {

    @Volatile
    private var cached: CoreModel? = null

    fun get(context: Context): CoreModel? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val m = try {
                context.assets.open("models/glyph_embed.bin").use { CoreModel.load(it.readBytes()) }
            } catch (e: Exception) {
                null
            }
            cached = m
            return m
        }
    }

    /** test hook */
    fun install(model: CoreModel) {
        cached = model
    }
}
