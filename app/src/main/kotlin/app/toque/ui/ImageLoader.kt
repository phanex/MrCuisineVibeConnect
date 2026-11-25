package app.toque.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Chargement d'images économe en mémoire (sous-échantillonnage + cache) pour 1 Go de RAM.
 * Après un défilement rapide de la grille, les cases visibles passent d'abord : la dernière
 * demandée est décodée la première, et une case qui montre déjà autre chose est sautée sans décoder.
 */
object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** File « dernier arrivé, premier servi ». */
    private class Lifo : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }

    private val exec = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, Lifo()) { r ->
        Thread(r, "toque-images").apply { priority = Thread.MIN_PRIORITY }
    }
    private val main = Handler(Looper.getMainLooper())

    fun load(view: ImageView, file: File?, targetWidth: Int) {
        val path = file?.absolutePath
        view.tag = path
        view.setImageDrawable(null)
        if (file == null || path == null) return
        val key = "$path@$targetWidth"
        cache.get(key)?.let { view.setImageBitmap(it); return }
        exec.execute {
            // La case a été réutilisée pour une autre recette entre-temps : rien à décoder.
            if (view.tag != path) return@execute
            val bmp = cache.get(key) ?: decode(file, targetWidth)?.also { cache.put(key, it) } ?: return@execute
            main.post { if (view.tag == path) view.setImageBitmap(bmp) }
        }
    }

    private fun decode(file: File, targetWidth: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    } catch (_: Throwable) {
        null
    }
}
