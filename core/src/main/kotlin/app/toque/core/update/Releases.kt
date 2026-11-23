package app.toque.core.update

import app.toque.core.json.Json

/** Une version publiée sur GitHub (page « Releases » du dépôt). */
data class Release(
    val tag: String,
    val version: String,
    val title: String,
    val notes: String,
    val apkName: String?,
    val apkUrl: String?,
    val apkSize: Long,
)

/** Lecture de l'API GitHub « dernière version » et comparaison de numéros de version. */
object Releases {
    const val REPO = "Wuki60/MrCuisineVibeConnect"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val PAGE_URL = "https://github.com/$REPO/releases"

    /** « v0.2.0 » → « 0.2.0 ». */
    fun versionOf(tag: String): String = tag.trim().removePrefix("v").removePrefix("V").trim()

    /** Compare deux versions « 1.2.3 » chiffre par chiffre (« 0.2 » = « 0.2.0 »). */
    fun compare(a: String, b: String): Int {
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    fun isNewer(candidate: String, installed: String): Boolean = compare(candidate, installed) > 0

    private fun parts(v: String): List<Int> =
        versionOf(v).split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /**
     * Marque de l'APK du Monsieur Cuisine Smart (mode sûr de la machine virtuelle) dans une version :
     * « Toque-smart-x.y.z.apk ». Rangé après « Toque-x.y.z.apk » par date d'envoi comme par ordre
     * alphabétique : les anciennes versions de Toque, qui prennent le premier APK, prennent celui du Connect.
     */
    const val SMART_PREFIX = "Toque-smart-"

    /**
     * Analyse la réponse de `GET /repos/{dépôt}/releases/latest`. Une version porte deux APK de la même
     * source : celui du Connect et celui du Smart ([SMART_PREFIX]). [smart] : prendre celui du Smart,
     * et seulement lui (sans lui, pas de mise à jour : l'APK du Connect plante sur sa tablette).
     * Les anciennes versions de Toque prennent le premier APK : celui du Connect doit être joint en premier.
     */
    fun parse(json: String, smart: Boolean = false): Release {
        @Suppress("UNCHECKED_CAST")
        val o = Json.parse(json) as? Map<String, Any?> ?: throw IllegalArgumentException("réponse GitHub illisible")
        val tag = o["tag_name"] as? String ?: throw IllegalArgumentException("version sans numéro")
        @Suppress("UNCHECKED_CAST")
        val assets = (o["assets"] as? List<Any?>).orEmpty().mapNotNull { it as? Map<String, Any?> }
        fun name(a: Map<String, Any?>) = (a["name"] as? String).orEmpty()
        val apks = assets.filter { name(it).endsWith(".apk", ignoreCase = true) }
        val apk = if (smart) apks.firstOrNull { name(it).startsWith(SMART_PREFIX, ignoreCase = true) }
        else apks.firstOrNull { !name(it).startsWith(SMART_PREFIX, ignoreCase = true) }
        return Release(
            tag = tag,
            version = versionOf(tag),
            title = (o["name"] as? String)?.takeIf { it.isNotBlank() } ?: tag,
            notes = (o["body"] as? String).orEmpty().trim(),
            apkName = apk?.get("name") as? String,
            apkUrl = apk?.get("browser_download_url") as? String,
            apkSize = (apk?.get("size") as? Number)?.toLong() ?: 0L,
        )
    }
}
