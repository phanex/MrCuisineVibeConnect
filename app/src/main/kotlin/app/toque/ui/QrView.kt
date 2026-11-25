package app.toque.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import app.toque.core.qr.QrCode

/**
 * Code QR dessiné sur un carré clair arrondi, avec sa zone de silence de 4 modules. Les modules ont
 * une taille entière en pixels (bords nets, plus facile à lire pour l'appareil photo). Toujours des
 * modules foncés sur fond clair, quel que soit le thème : un code en négatif ne se lit pas.
 */
class QrView(c: Context) : View(c) {
    var code: QrCode? = null
        set(v) {
            field = v
            invalidate()
        }

    private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LIGHT }
    private val dark = Paint().apply { color = DARK }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SURFACE2 }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.STROKE
        style = Paint.Style.STROKE
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = Ui.uf(16f)
        val qr = code
        if (qr == null) {
            // Pas de code à montrer : emplacement sombre, le message est posé dessus.
            border.strokeWidth = maxOf(1f, Ui.uf(1f))
            rect.set(border.strokeWidth / 2, border.strokeWidth / 2, w - border.strokeWidth / 2, h - border.strokeWidth / 2)
            canvas.drawRoundRect(rect, r, r, empty)
            canvas.drawRoundRect(rect, r, r, border)
            return
        }
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, light)
        if (Ui.light) {
            // Sur la carte blanche du thème clair, un filet délimite le carré du code.
            border.strokeWidth = maxOf(1f, Ui.uf(1f))
            rect.set(border.strokeWidth / 2, border.strokeWidth / 2, w - border.strokeWidth / 2, h - border.strokeWidth / 2)
            canvas.drawRoundRect(rect, r, r, border)
        }
        val modules = qr.size + 2 * QrCode.QUIET_ZONE
        val cell = (minOf(width, height) / modules).coerceAtLeast(1)
        val side = cell * qr.size
        val left = (width - side) / 2
        val top = (height - side) / 2
        for (y in 0 until qr.size) {
            var x = 0
            while (x < qr.size) {
                if (!qr.isDark(x, y)) { x++; continue }
                // Une suite de modules foncés de la même ligne : un seul rectangle.
                var end = x
                while (end + 1 < qr.size && qr.isDark(end + 1, y)) end++
                canvas.drawRect((left + x * cell).toFloat(), (top + y * cell).toFloat(),
                    (left + (end + 1) * cell).toFloat(), (top + (y + 1) * cell).toFloat(), dark)
                x = end + 1
            }
        }
    }

    private companion object {
        /** Fond et modules du code : fixes (ce sont le texte et le fond du thème sombre). */
        const val LIGHT = 0xFFF3F5F9.toInt()
        const val DARK = 0xFF0A1322.toInt()
    }
}
