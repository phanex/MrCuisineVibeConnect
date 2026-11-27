package app.toque.core

import app.toque.core.json.Json
import app.toque.core.machine.MachineController
import app.toque.core.protocol.Mcc
import app.toque.core.protocol.Smart
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.McOnline
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.SearchIndex
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepDurations
import app.toque.core.recipes.StepIngredients
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Micro-benchmarks reproductibles des chemins chauds de Toque (Connect & Smart).
 * Lancé uniquement avec `-Dtoque.bench=true` pour ne pas ralentir `./gradlew test`.
 */
class PerfBenchmarkTest {

    private fun bench(name: String, warmup: Int, iters: Int, block: () -> Any?) {
        var sink = 0
        repeat(warmup) { sink = sink xor (block()?.hashCode() ?: 0) }
        System.gc()
        val t0 = System.nanoTime()
        repeat(iters) { sink = sink xor (block()?.hashCode() ?: 0) }
        val elapsedNs = System.nanoTime() - t0
        val usPerOp = elapsedNs.toDouble() / iters / 1_000.0
        val opsPerSec = iters * 1_000_000_000.0 / elapsedNs
        println(String.format("BENCH | %-32s | %8.2f us/op | %10.0f ops/s | sink=%d", name, usPerOp, opsPerSec, sink and 0xFF))
    }

    private fun sampleMccRx(): ByteArray {
        val raw = ByteArray(Mcc.RX_SIZE)
        raw[0] = 0x55
        raw[1] = Mcc.RX_SIZE.toByte()
        raw[2] = 0xB1.toByte()
        raw[3] = 0x45 // motor+heater, bowl present, scale stable
        raw[4] = 0x00
        raw[6] = 3
        raw[9] = 0x05; raw[10] = 0xDC.toByte() // 1500 rpm
        raw[11] = 13 // 100 C
        raw[12] = 0x00; raw[13] = 100
        raw[14] = 0x00; raw[15] = 85
        raw[18] = 0x01; raw[19] = 0xF4.toByte() // 500 g
        raw[22] = 0x02 // normal
        raw[25] = Mcc.checksum(raw, 25).toByte()
        raw[26] = 0xAA.toByte()
        return raw
    }

    private fun sampleSmartRx(): ByteArray {
        val raw = ByteArray(Smart.RX_SIZE)
        raw[0] = Smart.START.toByte()
        raw[1] = 0
        raw[2] = Smart.RX_L.toByte()
        raw[3] = 0x01; raw[4] = 0x0F; raw[5] = 0x00; raw[6] = 0x00
        raw[7] = 12
        raw[8] = Smart.CMD_MAIN.toByte()
        raw[9] = Smart.CMD_SET.toByte()
        raw[12] = 0x00
        raw[17] = 14 // 100 C
        raw[20] = 0x00; raw[21] = 85
        raw[25] = 6 // speed 3
        raw[28] = Smart.FN_MANUAL.toByte()
        raw[32] = 0x01; raw[33] = 0xF4.toByte()
        raw[Smart.RX_L] = Smart.checksum(raw, Smart.RX_L).toByte()
        return raw
    }

    private fun syntheticRecipes(count: Int): List<Recipe> {
        val bases = listOf(
            "Soupe de potimarron et carottes", "Risotto aux champignons sauvages", "Poulet curry coco",
            "Gâteau au chocolat fondant", "Velouté de courgettes au basilic", "Bœuf bourguignon traditionnel",
            "Pâte à pizza napolitaine", "Ratatouille provençale", "Tarte aux pommes caramélisées",
            "Blanquette de veau à l'ancienne", "Compote de poires vanille", "Sauce bolognaise maison",
        )
        val ingNames = listOf(
            "potimarron", "carottes", "oignon", "gousse d'ail", "huile d'olive", "bouillon de légumes",
            "crème fraîche", "sel", "poivre", "sucre", "sucre vanillé", "farine de blé", "beurre doux",
            "tomates pelées", "champignons de Paris",
        )
        return List(count) { i ->
            val title = "${bases[i % bases.size]} n°${i + 1}"
            val ings = List(12) { j ->
                Ingredient(name = ingNames[(i + j) % ingNames.size], qty = "${(j + 1) * 50}", unit = "g")
            }
            val steps = List(6) { s ->
                Step(
                    text = "Étape ${s + 1} : Éplucher l'oignon et couper le potimarron en dés, puis laisser mijoter 25 à 30 min avec le bouillon et la crème fraîche.",
                    machine = if (s % 2 == 1) StepMachine(StepProgram.MANUAL, seconds = 300, speed = 3, tempC = 100) else null,
                )
            }
            Recipe(
                id = "bench-$i",
                title = title,
                category = if (i % 3 == 0) "Plats" else if (i % 3 == 1) "Soupes" else "Desserts",
                tags = listOf("rapide", "hiver", "famille"),
                servings = 4,
                totalMinutes = 20 + (i % 45),
                ingredients = listOf(IngredientGroup("", ings)),
                steps = steps,
                source = RecipeSource.IMPORTED,
                favorite = i % 10 == 0,
            )
        }
    }

    @Test
    fun runBenchmarks() {
        assumeTrue("Pass -Dtoque.bench=true to run benchmarks", System.getProperty("toque.bench") == "true")

        val mccRx = sampleMccRx()
        val smartRx = sampleSmartRx()
        val recipes = syntheticRecipes(2000)
        val index = SearchIndex(recipes)
        val sampleRecipe = recipes[0]
        val sampleJson = Json.write(RecipeCodec.encode(sampleRecipe))
        val sampleMap = RecipeCodec.encode(sampleRecipe)
        val stepText = "Éplucher l'oignon et les carottes, couper le potimarron en dés, ajouter le sucre vanillé et laisser mijoter 25 à 30 min à feu doux ou 1 h 15 au four."
        val sampleIngredients = sampleRecipe.allIngredients
        val sampleHtml = "<p>Éplucher   l&rsquo;oignon &amp; les carottes.<br/>Couper   en dés &quot;fins&quot;.</p><p>Laisser   reposer <b>30 min</b>.</p>"

        val fixture = HubFixture()
        val hub = fixture.hub
        val token = fixture.pair()
        val phone = hub.pairing.authenticate(token)!!
        hub.updateMachine(Snaps.running())

        val isInt = System.getProperty("java.vm.info", "").contains("interpreted", ignoreCase = true)
        println("=== TOQUE BENCHMARK (mode=${if (isInt) "INTERPRETER -Xint" else "JIT"}) ===")

        bench("Mcc.decodeRx", warmup = 5_000, iters = 50_000) { Mcc.decodeRx(mccRx) }
        bench("Smart.decodeRx", warmup = 5_000, iters = 50_000) { Smart.decodeRx(smartRx) }
        bench("Json.parse(recipe)", warmup = 500, iters = 5_000) { Json.parse(sampleJson) }
        bench("Json.write(recipe)", warmup = 500, iters = 5_000) { Json.write(sampleMap) }
        bench("Search.fuzzy(6+ chars, 2k)", warmup = 20, iters = 200) { index.search("potimaron") }
        bench("Search.empty(2k)", warmup = 50, iters = 500) { index.search("") }
        bench("StepDurations.find", warmup = 1_000, iters = 10_000) { StepDurations.find(stepText) }
        bench("StepIngredients.mentioned", warmup = 500, iters = 5_000) { StepIngredients.mentioned(sampleIngredients, stepText) }
        bench("McOnline.plain(html)", warmup = 500, iters = 5_000) { McOnline.plain(sampleHtml) }
        bench("CompanionHub.state+Json", warmup = 500, iters = 5_000) { Json.write(hub.state(phone).toMap()) }
    }
}
