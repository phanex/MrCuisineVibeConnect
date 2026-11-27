package app.toque.ui

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Chargement d'images économe en mémoire (sous-échantillonnage + mise à l'échelle + cache) pour 1 Go de RAM.
 * Après un défilement rapide de la grille, les cases visibles passent d'abord : la dernière
 * demandée est décodée la première, et une case qui montre déjà autre chose est sautée sans décoder.
 */
object ImageLoader {
    private const val MAX_QUEUE = 64

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt().coerceAtLeast(1024)) {
        override fun sizeOf(key: String, value: Bitmap): Int = maxOf(1, value.allocationByteCount / 1024)
    }

    /** File « dernier arrivé, premier servi », bornée : les plus anciennes demandes hors écran sont écartées. */
    private class Lifo : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean {
            while (size >= MAX_QUEUE) pollLast()
            return offerFirst(e)
        }
    }

    private val exec = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, Lifo()) { r ->
        Thread(r, "toque-images").apply { priority = Thread.MIN_PRIORITY }
    }
    private val main = Handler(Looper.getMainLooper())

    fun trimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cache.evictAll()
        else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) cache.trimToSize(cache.maxSize() / 2)
    }

    fun load(view: ImageView, file: File?, targetWidth: Int) {
        val path = file?.absolutePath
        view.tag = path
        view.setImageDrawable(null)
        if (file == null || path == null || targetWidth <= 0) return
        val key = "$path@$targetWidth"
        cache.get(key)?.let { view.setImageBitmap(it); return }
        val ref = WeakReference(view)
        exec.execute {
            val v = ref.get() ?: return@execute
            // La case a été réutilisée pour une autre recette entre-temps : rien à décoder.
            if (v.tag != path) return@execute
            val bmp = cache.get(key) ?: decode(file, targetWidth)?.also { cache.put(key, it) } ?: return@execute
            main.post {
                val target = ref.get()
                if (target != null && target.tag == path) target.setImageBitmap(bmp)
            }
        }
    }

    private fun decode(file: File, targetWidth: Int): Bitmap? = try {
        if (targetWidth <= 0) null
        else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val w = bounds.outWidth
            if (w <= 0) null
            else {
                var sample = 1
                while (w / (sample * 2) >= targetWidth) sample *= 2
                val sampledW = w / sample
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                    if (sampledW > targetWidth) {
                        inScaled = true
                        inDensity = sampledW
                        inTargetDensity = targetWidth
                    }
                })
            }
        }
    } catch (_: Throwable) {
        null
    }
}
