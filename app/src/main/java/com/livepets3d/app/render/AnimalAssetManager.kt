package com.livepets3d.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.livepets3d.app.anim.engine.ClipLibrary
import com.livepets3d.app.anim.engine.MiniJson
import java.util.concurrent.Executors

/**
 * Central access to one animal's animation library (assets/sprites/<id>/manifest.json) and its
 * sprite sheets (WebP or PNG + json). Sheets are decoded off the UI thread into a size-bounded
 * LRU cache, so a pet with many clips never holds every frame in memory at once.
 */
class AnimalAssetManager private constructor(private val context: Context, val id: String, val library: ClipLibrary,
                                             private val metas: Map<String, Meta>) {
    class Meta(val cols: Int, val frameWidth: Int, val frameHeight: Int, val footY: Int, val image: String)
    class Sheet(val bmp: Bitmap, val meta: Meta)

    private val cache = object : LinkedHashMap<String, Sheet>(16, 0.75f, true) {}
    private var bytes = 0L
    private val pending = HashSet<String>()
    @Volatile private var released = false

    /** Reference frame size: the engine and the renderer use the same artwork scale. */
    val refWidth: Int = metas["idle"]?.frameWidth ?: metas.values.firstOrNull()?.frameWidth ?: 360
    val refHeight: Int = metas["idle"]?.frameHeight ?: metas.values.firstOrNull()?.frameHeight ?: 340

    fun scaleFor(w: Float, h: Float) = minOf(w * 0.96f / refWidth, h * 0.9f / refHeight)

    /** The decoded sheet, or null while it is still being decoded in the background. */
    fun sheet(file: String): Sheet? {
        synchronized(cache) { cache[file]?.let { return it } }
        request(file)
        return null
    }

    fun prefetch(vararg files: String) = files.forEach { synchronized(cache) { if (!cache.containsKey(it)) request(it) } }

    private fun request(file: String) {
        val meta = metas[file] ?: return
        synchronized(cache) { if (!pending.add(file)) return }
        DECODER.execute {
            try {
                if (released) return@execute
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val bmp = context.assets.open("sprites/$id/${meta.image}").use { BitmapFactory.decodeStream(it, null, opts) } ?: return@execute
                synchronized(cache) {
                    if (released) { bmp.recycle(); return@execute }
                    cache[file] = Sheet(bmp, meta); bytes += bmp.byteCount
                    // evict least recently used sheets above the budget (never the one just added)
                    val it = cache.entries.iterator()
                    while (bytes > BUDGET && it.hasNext()) {
                        val e = it.next(); if (e.key == file) continue
                        bytes -= e.value.bmp.byteCount; e.value.bmp.recycle(); it.remove()
                    }
                }
            } catch (_: Throwable) {
            } finally { synchronized(cache) { pending.remove(file) } }
        }
    }

    fun release() {
        released = true
        synchronized(cache) { cache.values.forEach { it.bmp.recycle() }; cache.clear(); bytes = 0 }
    }

    companion object {
        /** ~11 MB per typical 16-frame sheet: keeps the clips in use plus a few recent ones. */
        private const val BUDGET = 64L * 1024 * 1024
        private val DECODER = Executors.newSingleThreadExecutor { r -> Thread(r, "pet-sheet-decoder").apply { priority = Thread.NORM_PRIORITY - 1 } }

        fun load(context: Context, id: String): AnimalAssetManager? = try {
            val files = (context.assets.list("sprites/$id") ?: emptyArray()).toSet()
            if ("manifest.json" !in files) null else {
                val metas = HashMap<String, Meta>()
                val lib = ClipLibrary.parse(context.assets.open("sprites/$id/manifest.json").bufferedReader().readText()) { f ->
                    if ("$f.json" !in files) null else {
                        val txt = context.assets.open("sprites/$id/$f.json").bufferedReader().readText()
                        val img = when { "$f.webp" in files -> "$f.webp"; "$f.png" in files -> "$f.png"; else -> null }
                        if (img == null) null else {
                            val m = MiniJson.parse(txt) as Map<*, *>
                            val fh = (m["frameHeight"] as Number).toInt()
                            metas[f] = Meta((m["cols"] as Number).toInt(), (m["frameWidth"] as Number).toInt(), fh,
                                ((m["footY"] ?: fh) as Number).toInt(), img)
                            txt
                        }
                    }
                }
                if (lib.clips.isEmpty()) null else AnimalAssetManager(context.applicationContext, id, lib, metas)
            }
        } catch (_: Exception) { null }
    }
}
