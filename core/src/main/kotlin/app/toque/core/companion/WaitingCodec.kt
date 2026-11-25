package app.toque.core.companion

import app.toque.core.json.Json
import app.toque.core.json.obj
import app.toque.core.json.str

/**
 * La recette en attente en texte JSON, pour la garder d'une relance de l'interface à l'autre
 * (voir [CompanionHub.restoreWaiting]). Un texte illisible ou incomplet donne null.
 */
object WaitingCodec {
    fun encode(w: WaitingRecipe): String = Json.write(linkedMapOf(
        "version" to 1,
        "recipeId" to w.recipeId,
        "title" to w.title,
        "stepCount" to w.stepCount,
        "totalMinutes" to w.totalMinutes,
        "servings" to w.servings,
        "category" to w.category,
        "user" to w.user,
        "hasImage" to w.hasImage,
        "sentAt" to w.sentAt,
        "phoneId" to w.sender.phoneId,
        "phoneName" to w.sender.name,
    ))

    fun decode(text: String?): WaitingRecipe? {
        if (text.isNullOrBlank()) return null
        return try {
            val m = Json.parse(text).obj()
            val id = m.str("recipeId")?.takeIf { it.isNotBlank() } ?: return null
            fun int(k: String) = (m[k] as? Number)?.toInt() ?: 0
            WaitingRecipe(
                recipeId = id,
                title = m.str("title").orEmpty(),
                stepCount = int("stepCount"),
                totalMinutes = int("totalMinutes"),
                servings = int("servings"),
                category = m.str("category").orEmpty(),
                user = m["user"] == true,
                hasImage = m["hasImage"] == true,
                sentAt = (m["sentAt"] as? Number)?.toLong() ?: 0L,
                sender = Actor(m.str("phoneId").orEmpty(), m.str("phoneName").orEmpty()),
            )
        } catch (_: Exception) {
            null
        }
    }
}
