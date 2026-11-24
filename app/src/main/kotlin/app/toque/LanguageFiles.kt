package app.toque

import android.content.Context
import app.toque.core.i18n.I18n
import app.toque.core.i18n.Language
import java.io.File

/**
 * Charge les langues : un fichier `<code>.json` par langue.
 * - fournis avec l'appli : `app/src/main/assets/lang/` ;
 * - ajoutés sur l'appareil, sans recompiler : `Android/data/app.toque/files/lang/` (prioritaires).
 */
object LanguageFiles {
    fun install(context: Context, log: Logbook) {
        val found = ArrayList<Language>()
        try {
            for (name in context.assets.list("lang").orEmpty().sorted()) {
                if (!name.endsWith(".json")) continue
                read(name, log) { context.assets.open("lang/$name").bufferedReader().use { it.readText() } }?.let { found += it }
            }
        } catch (e: Exception) {
            log.write("langue", "langues intégrées illisibles : $e")
        }
        val dir: File? = try { context.getExternalFilesDir("lang") } catch (_: Exception) { null }
        for (f in dir?.listFiles().orEmpty().sortedBy { it.name }) {
            if (!f.name.endsWith(".json")) continue
            read(f.name, log) { f.readText() }?.let { found += it }
        }
        val overlays = HashMap<String, Map<String, String>>()
        try {
            for (name in context.assets.list("lang_fork").orEmpty().sorted()) {
                if (!name.endsWith(".json")) continue
                read(name, log) { context.assets.open("lang_fork/$name").bufferedReader().use { it.readText() } }?.let {
                    overlays[it.code] = it.texts
                }
            }
        } catch (e: Exception) {
            log.write("langue", "langues fork illisibles : $e")
        }
        val merged = found.map { lang ->
            val extra = overlays[lang.code]
            if (extra.isNullOrEmpty()) lang else Language(lang.code, lang.name, lang.texts + extra)
        }
        I18n.install(merged)
    }

    private fun read(fileName: String, log: Logbook, text: () -> String): Language? = try {
        I18n.parse(fileName.removeSuffix(".json"), text())
    } catch (e: Exception) {
        log.write("langue", "fichier $fileName ignoré : ${e.message}")
        null
    }
}
