package app.toque.core.qr

/**
 * Code QR (modèle 2, ISO/IEC 18004) sans dépendance, en mode octets.
 *
 * Usage : `val qr = QrCode.encode("http://192.168.0.10:8080/#pair=…")`, puis dessiner un carré
 * foncé pour chaque `qr.isDark(x, y)` avec `x` et `y` dans `0 until qr.size` (x = colonne,
 * y = ligne, origine en haut à gauche).
 *
 * La matrice ne contient **pas** de zone de silence : le dessin doit laisser une marge claire de
 * [QUIET_ZONE] modules (4) de chaque côté, sinon beaucoup de lecteurs ne trouvent pas le code.
 *
 * Versions 1 à 40 ; la plus petite version qui contient les données est choisie. Le masque est
 * choisi par les règles de pénalité de la norme, sauf s'il est imposé (tests).
 */
class QrCode private constructor(
    /** Version (1 à 40) ; la taille vaut `4 × version + 17`. */
    val version: Int,
    /** Niveau de correction d'erreurs effectivement utilisé. */
    val errorCorrection: ErrorCorrection,
    /** Masque appliqué (0 à 7). */
    val mask: Int,
    private val modules: Array<BooleanArray>,
) {
    /** Côté de la matrice en modules, sans zone de silence. */
    val size: Int get() = modules.size

    /** Vrai si le module de la colonne [x] et de la ligne [y] est foncé. */
    fun isDark(x: Int, y: Int): Boolean = modules[y][x]

    /** Niveau de correction : part approximative du code qui peut être abîmée sans perte. */
    enum class ErrorCorrection(internal val formatBits: Int) {
        /** ~7 %. */ L(1),
        /** ~15 %. */ M(0),
        /** ~25 %. */ Q(3),
        /** ~30 %. */ H(2),
    }

    /** Le contenu ne tient pas dans la version 40 au niveau demandé. */
    class TooLongException(val length: Int, val capacity: Int, level: ErrorCorrection) :
        IllegalArgumentException("contenu trop long pour un code QR : $length octets, $capacity au plus au niveau $level")

    companion object {
        /** Marge claire à laisser autour de la matrice, en modules. */
        const val QUIET_ZONE = 4
        const val MIN_VERSION = 1
        const val MAX_VERSION = 40

        /** Encode les octets UTF-8 de [text]. Voir l'autre [encode]. */
        fun encode(text: String, level: ErrorCorrection = ErrorCorrection.M, mask: Int? = null): QrCode =
            encode(text.toByteArray(Charsets.UTF_8), level, mask)

        /**
         * Encode [data] en mode octets, dans la plus petite version qui convient.
         * [mask] (0 à 7) impose un masque ; `null` le choisit selon les pénalités.
         * @throws TooLongException si les données ne tiennent pas dans la version 40.
         */
        fun encode(data: ByteArray, level: ErrorCorrection = ErrorCorrection.M, mask: Int? = null): QrCode {
            require(mask == null || mask in 0..7) { "masque invalide : $mask" }
            var version = MIN_VERSION
            while (true) {
                if (bitsNeeded(data.size, version) <= dataCodewords(version, level) * 8) break
                if (version == MAX_VERSION) throw TooLongException(data.size, capacity(MAX_VERSION, level), level)
                version++
            }
            val codewords = addEcc(dataCodewords(data, version, level), version, level)
            val m = Matrix(version)
            m.drawFunctionPatterns()
            m.drawCodewords(codewords)
            val chosen = mask ?: (0..7).minByOrNull { k ->
                m.applyMask(k); m.drawFormat(level, k)
                val p = m.penalty()
                m.applyMask(k) // le masque est un XOR : le réappliquer l'annule
                p
            }!!
            m.applyMask(chosen)
            m.drawFormat(level, chosen)
            return QrCode(version, level, chosen, m.modules)
        }

        /** Nombre maximal d'octets en mode octets pour [version] et [level]. */
        fun capacity(version: Int, level: ErrorCorrection): Int {
            require(version in MIN_VERSION..MAX_VERSION) { "version invalide : $version" }
            return (dataCodewords(version, level) * 8 - 4 - countBits(version)) / 8
        }

        // --- Tables de la norme (index = version, 0 inutilisé), dans l'ordre L, M, Q, H. ---

        private val ECC_PER_BLOCK = arrayOf(
            intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
            intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        )

        private val BLOCKS = arrayOf(
            intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
            intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
            intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
            intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
        )

        private fun row(level: ErrorCorrection) = when (level) {
            ErrorCorrection.L -> 0
            ErrorCorrection.M -> 1
            ErrorCorrection.Q -> 2
            ErrorCorrection.H -> 3
        }

        private fun countBits(version: Int) = if (version <= 9) 8 else 16

        /** Indicateur de mode + longueur + données (sans terminateur). */
        private fun bitsNeeded(length: Int, version: Int): Int =
            if (length >= (1 shl countBits(version))) Int.MAX_VALUE else 4 + countBits(version) + 8 * length

        /** Modules disponibles pour les données et la correction, une fois les motifs fixes placés. */
        private fun rawModules(version: Int): Int {
            var n = (16 * version + 128) * version + 64
            if (version >= 2) {
                val align = version / 7 + 2
                n -= (25 * align - 10) * align - 55
                if (version >= 7) n -= 36
            }
            return n
        }

        private fun dataCodewords(version: Int, level: ErrorCorrection): Int =
            rawModules(version) / 8 - ECC_PER_BLOCK[row(level)][version] * BLOCKS[row(level)][version]

        /** Flux de bits : mode octets, longueur, données, terminateur, octets de bourrage. */
        private fun dataCodewords(data: ByteArray, version: Int, level: ErrorCorrection): ByteArray {
            val capacityBits = dataCodewords(version, level) * 8
            val bits = BitBuffer(capacityBits)
            bits.append(0b0100, 4)
            bits.append(data.size, countBits(version))
            for (b in data) bits.append(b.toInt() and 0xFF, 8)
            bits.append(0, minOf(4, capacityBits - bits.length))
            bits.append(0, (8 - bits.length % 8) % 8)
            var pad = 0xEC
            while (bits.length < capacityBits) { bits.append(pad, 8); pad = pad xor 0xEC xor 0x11 }
            return bits.bytes
        }

        /** Découpe en blocs, ajoute la correction Reed-Solomon de chacun et entrelace. */
        private fun addEcc(data: ByteArray, version: Int, level: ErrorCorrection): ByteArray {
            val numBlocks = BLOCKS[row(level)][version]
            val eccLen = ECC_PER_BLOCK[row(level)][version]
            val raw = rawModules(version) / 8
            val numShort = numBlocks - raw % numBlocks
            val shortLen = raw / numBlocks // longueur d'un bloc court, correction comprise
            val divisor = ReedSolomon.divisor(eccLen)
            val dataBlocks = ArrayList<ByteArray>(numBlocks)
            val eccBlocks = ArrayList<ByteArray>(numBlocks)
            var k = 0
            for (i in 0 until numBlocks) {
                val len = shortLen - eccLen + (if (i < numShort) 0 else 1)
                val block = data.copyOfRange(k, k + len)
                k += len
                dataBlocks.add(block)
                eccBlocks.add(ReedSolomon.remainder(block, divisor))
            }
            val out = ByteArray(raw)
            var n = 0
            for (i in 0..shortLen - eccLen) for (b in dataBlocks) if (i < b.size) out[n++] = b[i]
            for (i in 0 until eccLen) for (b in eccBlocks) out[n++] = b[i]
            check(n == raw)
            return out
        }
    }

    private class BitBuffer(capacityBits: Int) {
        val bytes = ByteArray((capacityBits + 7) / 8)
        var length = 0

        fun append(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                if ((value ushr i) and 1 != 0) bytes[length ushr 3] = (bytes[length ushr 3].toInt() or (0x80 ushr (length and 7))).toByte()
                length++
            }
        }
    }

    /** Reed-Solomon sur GF(256), polynôme 0x11D, racines α⁰…α^(n-1). */
    private object ReedSolomon {
        fun multiply(a: Int, b: Int): Int {
            var r = 0
            for (i in 7 downTo 0) {
                r = (r shl 1) xor ((r ushr 7) * 0x11D)
                r = r xor (((b ushr i) and 1) * a)
            }
            return r
        }

        /** Coefficients du générateur de degré [degree], sans le terme de tête (qui vaut 1). */
        fun divisor(degree: Int): IntArray {
            val d = IntArray(degree)
            d[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in 0 until degree) {
                    d[j] = multiply(d[j], root)
                    if (j + 1 < degree) d[j] = d[j] xor d[j + 1]
                }
                root = multiply(root, 0x02)
            }
            return d
        }

        fun remainder(data: ByteArray, divisor: IntArray): ByteArray {
            val r = IntArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() and 0xFF) xor r[0]
                System.arraycopy(r, 1, r, 0, r.size - 1)
                r[r.size - 1] = 0
                for (i in r.indices) r[i] = r[i] xor multiply(divisor[i], factor)
            }
            return ByteArray(r.size) { r[it].toByte() }
        }
    }

    /** Matrice en construction : modules et marque des modules de fonction (non masqués). */
    private class Matrix(val version: Int) {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val function = Array(size) { BooleanArray(size) }

        fun set(x: Int, y: Int, dark: Boolean) { modules[y][x] = dark; function[y][x] = true }

        fun drawFunctionPatterns() {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
            val pos = alignmentPositions()
            val last = pos.size - 1
            for (i in pos.indices) for (j in pos.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) set(pos[i] + dx, pos[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
            }
            drawFormat(ErrorCorrection.M, 0) // réserve les emplacements ; réécrit à la fin
            drawVersion()
        }

        private fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x !in 0 until size || y !in 0 until size) continue
                val d = maxOf(Math.abs(dx), Math.abs(dy))
                set(x, y, d != 2 && d != 4)
            }
        }

        private fun alignmentPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val n = version / 7 + 2
            val step = (version * 8 + n * 3 + 5) / (n * 4 - 4) * 2
            val r = IntArray(n)
            r[0] = 6
            var p = size - 7
            for (i in n - 1 downTo 1) { r[i] = p; p -= step }
            return r
        }

        fun drawFormat(level: ErrorCorrection, mask: Int) {
            val data = (level.formatBits shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6))
            set(8, 8, bit(7))
            set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true) // module toujours foncé
        }

        private fun drawVersion() {
            if (version < 7) return
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val dark = (bits ushr i) and 1 != 0
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, dark)
                set(b, a, dark)
            }
        }

        /** Place les mots de code en zigzag, colonnes de deux modules, de bas à droite. */
        fun drawCodewords(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                val upward = ((right + 1) and 2) == 0
                for (vert in 0 until size) {
                    val y = if (upward) size - 1 - vert else vert
                    for (j in 0..1) {
                        val x = right - j
                        if (!function[y][x] && i < data.size * 8) {
                            modules[y][x] = ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                            i++
                        }
                        // les modules restants (reste de 0 à 7 bits) restent clairs
                    }
                }
                right -= 2
            }
            check(i == data.size * 8)
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                if (function[y][x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert) modules[y][x] = !modules[y][x]
            }
        }

        /**
         * Pénalités N1 à N4 de la norme. Pour N3, les 4 modules clairs doivent être dans la matrice
         * (même convention que ZXing, ce qui permet de comparer les codes module par module).
         */
        fun penalty(): Int {
            var p = 0
            fun at(x: Int, y: Int, horizontal: Boolean) = if (horizontal) modules[y][x] else modules[x][y]
            fun light(line: Int, from: Int, to: Int, horizontal: Boolean): Boolean {
                if (from < 0 || to > size) return false
                for (i in from until to) if (at(i, line, horizontal)) return false
                return true
            }
            for (horizontal in booleanArrayOf(true, false)) for (line in 0 until size) {
                // N1 : suites d'au moins 5 modules de même couleur
                var run = 1
                for (i in 1..size) {
                    if (i < size && at(i, line, horizontal) == at(i - 1, line, horizontal)) { run++; continue }
                    if (run >= 5) p += 3 + (run - 5)
                    run = 1
                }
                // N3 : 1011101 précédé ou suivi de 4 modules clairs
                for (i in 0..size - 7) {
                    if (at(i, line, horizontal) && !at(i + 1, line, horizontal) && at(i + 2, line, horizontal) &&
                        at(i + 3, line, horizontal) && at(i + 4, line, horizontal) && !at(i + 5, line, horizontal) &&
                        at(i + 6, line, horizontal) &&
                        (light(line, i - 4, i, horizontal) || light(line, i + 7, i + 11, horizontal))
                    ) p += 40
                }
            }
            // N2 : blocs 2×2 de même couleur
            var dark = 0
            for (y in 0 until size) for (x in 0 until size) {
                val c = modules[y][x]
                if (c) dark++
                if (x + 1 < size && y + 1 < size && c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) p += 3
            }
            // N4 : écart à 50 % de modules foncés, par pas de 5 %
            val total = size * size
            p += Math.abs(dark * 2 - total) * 10 / total * 10
            return p
        }
    }
}
