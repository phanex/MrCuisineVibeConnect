package app.toque.core

import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.StepIngredients
import org.junit.Assert.assertEquals
import org.junit.Test

class StepIngredientsTest {
    private fun names(list: List<Ingredient>, text: String): List<String> =
        StepIngredients.mentioned(list, text).map { list[it].name }

    private val veloute = listOf(
        Ingredient("1", "", "oignon"), Ingredient("20", "g", "de beurre"), Ingredient("600", "g", "de potimarron"),
        Ingredient("500", "g", "d'eau"), Ingredient("1", "", "cube de bouillon de légumes"), Ingredient("100", "g", "de crème liquide"),
        Ingredient("1", "pincée", "de noix de muscade"), Ingredient("", "", "sel, poivre"), Ingredient("2", "", "gousses d'ail"),
    )

    @Test fun french() {
        assertEquals(listOf("oignon", "de potimarron"), names(veloute, "Éplucher l'oignon et couper le potimarron en morceaux."))
        assertEquals(listOf("de beurre"), names(veloute, "Mettre le beurre dans le bol."))
        // Pluriel dans l'étape, singulier dans la liste ; ordre de la liste, pas de l'étape.
        assertEquals(listOf("oignon", "de potimarron"), names(veloute, "Ajouter les potimarrons et les oignons."))
        // Le mot après le contenant : gousses d'ail → ail, cube de bouillon → bouillon, noix de muscade → muscade.
        assertEquals(listOf("cube de bouillon de légumes", "gousses d'ail"), names(veloute, "Ajouter l'ail et le bouillon."))
        assertEquals(listOf("de noix de muscade"), names(veloute, "Râper un peu de muscade."))
        // « crème » suffit pour « crème liquide » (un seul ingrédient porte ce mot).
        assertEquals(listOf("de crème liquide"), names(veloute, "Ajouter la crème et mixer."))
        assertEquals(listOf("d'eau"), names(veloute, "Verser l'eau."))
        // « sel, poivre » : le premier mot seulement.
        assertEquals(listOf("sel, poivre"), names(veloute, "Rectifier en sel."))
    }

    @Test fun nothingRatherThanSomethingWrong() {
        // Aucun ingrédient nommé.
        assertEquals(emptyList<String>(), names(veloute, "Servir chaud."))
        assertEquals(emptyList<String>(), names(veloute, ""))
        // Un verbe n'est pas l'ingrédient : « beurrer » n'est pas « beurre », « saler » n'est pas « sel ».
        assertEquals(emptyList<String>(), names(veloute, "Beurrer le moule et saler."))
        // Deux ingrédients partagent le mot et l'étape ne dit pas lequel : aucun.
        val two = listOf(Ingredient("200", "g", "farine de blé"), Ingredient("50", "g", "farine de seigle"))
        assertEquals(emptyList<String>(), names(two, "Ajouter la farine."))
        assertEquals(listOf("farine de seigle"), names(two, "Ajouter la farine de seigle."))
        // Mots trop courts pour être sûrs.
        assertEquals(emptyList<String>(), names(listOf(Ingredient("1", "", "œ")), "Ajouter l'œ."))
    }

    @Test fun mostPreciseIngredientWins() {
        val sugars = listOf(Ingredient("100", "g", "sucre"), Ingredient("1", "sachet", "sucre vanillé"))
        assertEquals(listOf("sucre vanillé"), names(sugars, "Ajouter le sucre vanillé."))
        assertEquals(listOf("sucre"), names(sugars, "Ajouter le sucre."))
        // Les deux écrits : les deux.
        assertEquals(listOf("sucre", "sucre vanillé"), names(sugars, "Ajouter le sucre et le sucre vanillé."))
        // Huile d'olive et olives : « les olives » ne désigne pas l'huile.
        val olives = listOf(Ingredient("2", "c. s.", "huile d'olive"), Ingredient("100", "g", "olives noires"))
        assertEquals(listOf("olives noires"), names(olives, "Ajouter les olives."))
        assertEquals(listOf("huile d'olive"), names(olives, "Verser l'huile."))
    }

    @Test fun sameIngredientInTwoGroups() {
        val list = listOf(Ingredient("20", "g", "beurre"), Ingredient("200", "g", "farine"), Ingredient("50", "g", "beurre"))
        assertEquals(listOf(0, 2), StepIngredients.mentioned(list, "Faire fondre le beurre."))
    }

    @Test fun otherLanguages() {
        val en = listOf(Ingredient("1", "", "large onion"), Ingredient("2", "", "cloves of garlic"), Ingredient("400", "g", "chopped tomatoes"))
        assertEquals(listOf("large onion", "cloves of garlic"), names(en, "Peel the onion and the garlic."))
        val es = listOf(Ingredient("1", "", "cebolla"), Ingredient("2", "", "dientes de ajo"), Ingredient("3", "", "huevos"))
        assertEquals(listOf("cebolla", "dientes de ajo", "huevos"), names(es, "Pelar la cebolla y el ajo, y batir los huevos."))
        val de = listOf(Ingredient("2", "", "Zwiebeln"), Ingredient("1", "", "Knoblauchzehe"), Ingredient("200", "g", "Mehl"))
        assertEquals(listOf("Zwiebeln", "Mehl"), names(de, "Die Zwiebel schälen und das Mehl sieben."))
        val ca = listOf(Ingredient("1", "", "ceba"), Ingredient("2", "", "grans d'all"), Ingredient("3", "", "ous"))
        assertEquals(listOf("ceba", "grans d'all"), names(ca, "Peleu la ceba i l'all."))
        assertEquals(listOf("Mehl"), names(listOf(Ingredient("500", "g", "Mehl"), Ingredient("1", "Prise", "Salz")), "Mehl in den Mixtopf geben."))
    }
}
