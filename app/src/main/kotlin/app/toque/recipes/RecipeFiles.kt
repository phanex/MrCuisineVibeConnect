package app.toque.recipes

import android.os.Environment
import app.toque.Logbook
import app.toque.core.i18n.tr
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.UserRecipes
import java.io.File

/**
 * Mes recettes dans un fichier « toque-recettes-1 » du dossier Download du robot : pour les
 * sauvegarder, ou les passer d'un robot à l'autre (adb push / pull, ou un câble USB).
 * À appeler hors du fil de l'interface.
 */
class RecipeFiles(private val repo: RecipeRepo, private val log: Logbook) {
    /** [toAdapt] : étapes importées dont le réglage machine est hors des règles (à adapter au moment de cuisiner). */
    class Result(val ok: Boolean, val message: String, val toAdapt: Int = 0)

    private val dir: File get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    fun export(): Result {
        val list = repo.userRecipes()
        if (list.isEmpty()) return Result(false, tr("Aucune recette perso à exporter"))
        return try {
            dir.mkdirs()
            val file = File(dir, UserRecipes.FILE_NAME)
            val tmp = File(dir, UserRecipes.FILE_NAME + ".part")
            tmp.writeText(RecipeCodec.encodeFile(list))
            if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw java.io.IOException(tr("écriture impossible")) }
            log.write("recettes", "export : ${list.size} recettes perso dans ${file.path}")
            Result(true, tr("%d recettes exportées dans Download/%s", list.size, UserRecipes.FILE_NAME))
        } catch (e: Exception) {
            log.write("recettes", "export impossible : $e")
            Result(false, tr("Export impossible : %s", e.message ?: e.toString()))
        }
    }

    /** Lit tous les fichiers de recettes Toque du dossier Download ; une recette déjà là est mise à jour. */
    fun import(): Result {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json", ignoreCase = true) && f.length() < 8 * 1024 * 1024 }.orEmpty()
        val present = repo.ids()
        var found = 0
        var added = 0
        var updated = 0
        var bad = 0
        var toAdapt = 0
        for (f in files) {
            val text = try { f.readText() } catch (_: Exception) { continue }
            // Les autres fichiers JSON du dossier ne sont pas des recettes Toque.
            if (!text.contains(RecipeCodec.FORMAT)) continue
            found++
            val (list, unreadable) = try {
                RecipeCodec.decodeFile(text, RecipeSource.USER)
            } catch (e: Exception) {
                log.write("recettes", "fichier illisible ${f.name} : $e")
                bad++
                continue
            }
            bad += unreadable
            val mine = list.map { reviewed(UserRecipes.imported(it)) }
            for (r in mine) toAdapt += adapt(r)
            if (mine.isEmpty()) continue
            repo.upsertAll(mine, rebuild = false)
            for (r in mine) if (r.id in present) updated++ else added++
        }
        if (found == 0) return Result(false, tr("Aucun fichier de recettes Toque dans Download"))
        repo.refresh()
        log.write("recettes", "import : $added nouvelles, $updated mises à jour, $bad illisibles, $toAdapt étapes à adapter ($found fichiers)")
        if (added + updated == 0) return Result(false, tr("Aucune recette lisible dans les fichiers de Download"))
        val note = if (bad > 0) " " + tr("(%d illisibles)", bad) else ""
        val adapt = if (toAdapt > 0) " ; " + tr("%d étapes ont un réglage à adapter au moment de cuisiner", toAdapt) else ""
        return Result(true, tr("%d recettes ajoutées, %d mises à jour", added, updated) + note + adapt, toAdapt)
    }

    companion object {
        /**
         * Un fichier ne dit pas ce qui a été vérifié sur ce robot : chaque étape avec un réglage machine
         * devra être confirmée sur le robot avant son premier lancement, quoi que dise le fichier.
         */
        fun reviewed(r: Recipe): Recipe = r.copy(steps = r.steps.map { if (it.machine != null && !it.needsReview) it.copy(needsReview = true) else it })

        /**
         * Étapes dont le réglage est hors des règles : importées quand même, bloquées à la cuisson comme
         * aujourd'hui. Un réglage que le cœur refuse de décrire (programme d'un autre robot à une
         * température hors de sa plage) compte aussi.
         */
        fun adapt(r: Recipe): Int = r.steps.count { s ->
            val m = s.machine ?: return@count false
            try {
                !m.valid
            } catch (_: RuntimeException) {
                true
            }
        }
    }
}
