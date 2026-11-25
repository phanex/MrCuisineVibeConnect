package app.toque.core.companion.demo

import app.toque.core.machine.Accessory
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import app.toque.core.recipes.Weigh
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Catalogue inventé pour la démo (aucun contenu Lidl). */
object DemoCatalog {
    private fun ing(qty: String, unit: String, name: String) = Ingredient(qty, unit, name)
    private fun hand(t: String) = Step(t)
    private fun weigh(t: String, label: String, g: Int) = Step(t, weigh = Weigh(label, g))
    private fun cook(t: String, s: Int, speed: Int, temp: Int = 0, reverse: Boolean = false, whisk: Boolean = false) =
        Step(t, StepMachine(StepProgram.MANUAL, s, speed, temp, reverse, if (whisk) Accessory.WHISK else Accessory.BLADE))

    val VELOUTE_ID = "perso-veloute-de-potimarron"

    val recipes: List<Recipe> = listOf(
        Recipe(
            id = VELOUTE_ID, title = "Velouté de potimarron", category = "Soupes", servings = 4, prepMinutes = 10,
            totalMinutes = 35, difficulty = 1, source = RecipeSource.USER, image = "veloute.png", favorite = true,
            ingredients = listOf(IngredientGroup(items = listOf(
                ing("1", "", "Oignon"), ing("20", "g", "Beurre"), ing("600", "g", "Potimarron"),
                ing("500", "g", "Eau"), ing("100", "g", "Crème"), ing("", "", "Sel, poivre"),
            ))),
            steps = listOf(
                hand("Éplucher l'oignon et couper le potimarron en morceaux."),
                weigh("Mettre le beurre dans le bol.", "Beurre", 20),
                cook("Faire revenir l'oignon.", 180, 1, 100),
                weigh("Ajouter le potimarron et l'eau.", "Eau", 500),
                cook("Cuire jusqu'à ce que le potimarron soit tendre.", 1200, 1, 100),
                Step("Ajouter la crème et mixer.", StepMachine(StepProgram.MANUAL, 60, 8, 0), Weigh("Crème", 100)),
            ),
        ),
        Recipe(id = "pate-a-pizza", title = "Pâte à pizza", category = "Pâtes", servings = 2, totalMinutes = 7, image = "pizza.png",
            ingredients = listOf(IngredientGroup(items = listOf(ing("300", "g", "Farine"), ing("180", "g", "Eau tiède"),
                ing("1", "c. à café", "Levure sèche"), ing("1", "pincée", "Sel")))),
            steps = listOf(weigh("Verser l'eau tiède et la levure.", "Eau tiède", 180), cook("Mélanger.", 20, 2, 37),
                weigh("Ajouter la farine et le sel.", "Farine", 300),
                Step("Pétrir.", StepMachine(StepProgram.KNEAD, 90)))),
        Recipe(id = "risotto-champignons", title = "Risotto aux champignons", category = "Plats", servings = 4, totalMinutes = 40,
            ingredients = listOf(IngredientGroup(items = listOf(ing("250", "g", "Riz rond"), ing("200", "g", "Champignons"),
                ing("750", "g", "Bouillon"), ing("1", "", "Échalote")))),
            steps = listOf(hand("Émincer l'échalote et les champignons."), cook("Faire revenir l'échalote.", 180, 1, 100),
                weigh("Ajouter le riz.", "Riz rond", 250), cook("Nacrer le riz.", 120, 1, 100, reverse = true),
                weigh("Verser le bouillon.", "Bouillon", 750), cook("Cuire.", 1080, 1, 95, reverse = true))),
        Recipe(id = "compote-pommes", title = "Compote de pommes", category = "Desserts", servings = 4, totalMinutes = 20, image = "compote.png",
            ingredients = listOf(IngredientGroup(items = listOf(ing("800", "g", "Pommes"), ing("50", "g", "Eau"), ing("1", "", "Gousse de vanille")))),
            steps = listOf(weigh("Ajouter les pommes en quartiers.", "Pommes", 800), cook("Cuire.", 900, 1, 100, reverse = true),
                cook("Mixer.", 20, 5))),
        Recipe(id = "soupe-legumes", title = "Soupe de légumes", category = "Soupes", servings = 4, totalMinutes = 30,
            ingredients = listOf(IngredientGroup(items = listOf(ing("600", "g", "Légumes variés"), ing("700", "g", "Eau")))),
            steps = listOf(hand("Couper les légumes."), weigh("Ajouter les légumes.", "Légumes variés", 600),
                weigh("Ajouter l'eau.", "Eau", 700), cook("Cuire.", 1500, 1, 100), cook("Mixer.", 60, 7))),
        Recipe(id = "creme-anglaise", title = "Crème anglaise", category = "Desserts", servings = 6, totalMinutes = 15, favorite = true,
            ingredients = listOf(IngredientGroup(items = listOf(ing("500", "g", "Lait"), ing("4", "", "Jaunes d'œufs"), ing("80", "g", "Sucre")))),
            steps = listOf(weigh("Verser le lait.", "Lait", 500), weigh("Ajouter le sucre.", "Sucre", 80),
                hand("Ajouter les jaunes d'œufs."), cook("Cuire en remuant.", 480, 3, 80))),
        Recipe(id = "houmous", title = "Houmous aux pois chiches", category = "Entrées", servings = 4, totalMinutes = 10,
            ingredients = listOf(IngredientGroup(items = listOf(ing("400", "g", "Pois chiches cuits"), ing("2", "c. à soupe", "Tahin"), ing("1", "", "Citron")))),
            steps = listOf(weigh("Mettre les pois chiches.", "Pois chiches cuits", 400), hand("Ajouter le tahin et le jus de citron."),
                cook("Mixer.", 45, 6))),
        Recipe(id = "pesto-basilic", title = "Pesto de basilic", category = "Sauces", servings = 4, totalMinutes = 5,
            ingredients = listOf(IngredientGroup(items = listOf(ing("50", "g", "Basilic"), ing("40", "g", "Parmesan"), ing("100", "g", "Huile d'olive")))),
            steps = listOf(weigh("Mettre le parmesan.", "Parmesan", 40), cook("Râper.", 10, 8), weigh("Ajouter le basilic.", "Basilic", 50),
                weigh("Ajouter l'huile.", "Huile d'olive", 100), cook("Mixer.", 20, 5))),
        Recipe(id = "puree-carottes", title = "Purée de carottes", category = "Accompagnements", servings = 4, totalMinutes = 30,
            ingredients = listOf(IngredientGroup(items = listOf(ing("700", "g", "Carottes"), ing("300", "g", "Eau"), ing("20", "g", "Beurre")))),
            steps = listOf(weigh("Ajouter les carottes en rondelles.", "Carottes", 700), weigh("Ajouter l'eau.", "Eau", 300),
                cook("Cuire.", 1320, 1, 100), hand("Égoutter et remettre dans le bol."), cook("Mixer.", 30, 5))),
        Recipe(id = "riz-au-lait", title = "Riz au lait", category = "Desserts", servings = 4, totalMinutes = 45,
            ingredients = listOf(IngredientGroup(items = listOf(ing("120", "g", "Riz rond"), ing("800", "g", "Lait"), ing("70", "g", "Sucre")))),
            steps = listOf(weigh("Verser le lait.", "Lait", 800), weigh("Ajouter le riz.", "Riz rond", 120),
                cook("Cuire.", 2400, 1, 90, reverse = true), weigh("Ajouter le sucre.", "Sucre", 70), cook("Mélanger.", 30, 1, reverse = true))),
        Recipe(id = "sauce-tomate", title = "Sauce tomate maison", category = "Sauces", servings = 6, totalMinutes = 35, image = "tomate.png",
            ingredients = listOf(IngredientGroup(items = listOf(ing("800", "g", "Tomates"), ing("1", "", "Oignon"), ing("1", "gousse", "Ail")))),
            steps = listOf(hand("Couper l'oignon et l'ail."), cook("Hacher.", 5, 5), cook("Faire revenir.", 240, 1, 100),
                weigh("Ajouter les tomates.", "Tomates", 800), cook("Cuire.", 1500, 1, 100, reverse = true))),
        Recipe(id = "chantilly", title = "Crème chantilly", category = "Desserts", servings = 6, totalMinutes = 5,
            ingredients = listOf(IngredientGroup(items = listOf(ing("400", "g", "Crème entière froide"), ing("40", "g", "Sucre glace")))),
            steps = listOf(hand("Mettre le fouet."), weigh("Verser la crème.", "Crème entière froide", 400),
                weigh("Ajouter le sucre glace.", "Sucre glace", 40), cook("Fouetter.", 120, 4, whisk = true))),
    )

    val byId: Map<String, Recipe> = recipes.associateBy { it.id }
    val recent: List<String> = listOf(VELOUTE_ID, "pate-a-pizza", "compote-pommes", "creme-anglaise")
    val frequent: List<String> = listOf("pate-a-pizza", VELOUTE_ID, "soupe-legumes")

    /** Petite image PNG unie, couleur tirée de l'identifiant. */
    fun image(id: String): ByteArray? {
        val r = byId[id] ?: return null
        if (r.image.isBlank()) return null
        val h = id.hashCode()
        return png(48, 32, (h shr 16) and 0xFF, (h shr 8) and 0xFF, h and 0xFF)
    }

    private fun png(w: Int, hgt: Int, red: Int, green: Int, blue: Int): ByteArray {
        val raw = ByteArrayOutputStream()
        for (y in 0 until hgt) {
            raw.write(0)
            for (x in 0 until w) { raw.write(red); raw.write(green); raw.write(blue) }
        }
        val def = Deflater()
        def.setInput(raw.toByteArray())
        def.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!def.finished()) z.write(buf, 0, def.deflate(buf))
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            d.writeInt(data.size)
            val t = type.toByteArray(Charsets.US_ASCII)
            d.write(t); d.write(data)
            val crc = CRC32(); crc.update(t); crc.update(data)
            d.writeInt(crc.value.toInt())
        }
        val ihdr = ByteArrayOutputStream()
        DataOutputStream(ihdr).apply { writeInt(w); writeInt(hgt); write(8); write(2); write(0); write(0); write(0) }
        chunk("IHDR", ihdr.toByteArray())
        chunk("IDAT", z.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}
