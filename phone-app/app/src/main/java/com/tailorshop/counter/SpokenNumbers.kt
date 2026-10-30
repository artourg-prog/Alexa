package com.tailorshop.counter

// Turns number words the recognizer leaves as words into digits, in EN and RU, so
// "six eight to for", "eighty four thirty eight", "шестьдесят восемь двадцать четыре"
// and "одна тысяча двести восемь" all become digit strings. Works on Lookup.normalize()
// text (Cyrillic already transliterated to Latin).
object SpokenNumbers {
    private const val HUNDRED = -100
    private const val THOUSAND = -1000

    private val UNITS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9,
        // RU
        "nol" to 0, "nul" to 0, "odin" to 1, "odna" to 1, "odno" to 1, "dva" to 2, "dve" to 2, "tri" to 3,
        "chetyre" to 4, "pyat" to 5, "shest" to 6, "sem" to 7, "vosem" to 8, "devyat" to 9,
        // English said to a Russian recognizer, written in Cyrillic ("сикс эйт")
        "ziro" to 0, "zero" to 0, "van" to 1, "fri" to 3, "sri" to 3, "fayv" to 5, "fayf" to 5, "siks" to 6,
        "sevn" to 7, "eyt" to 8, "eit" to 8, "nayn" to 9,
    )

    // Real digits only when next to other number words ("six eight to for").
    // Includes common mishearings of digits ("sex side to for" = 6824).
    private val SOFT = mapOf(
        "oh" to 0, "to" to 2, "too" to 2, "tu" to 2, "for" to 4, "fo" to 4, "fore" to 4, "ate" to 8, "won" to 1,
        "sex" to 6, "sax" to 6, "side" to 8, "sight" to 8, "site" to 8, "tree" to 3, "free" to 3, "fife" to 5, "nein" to 9,
    )
    // Sentence glue that often leads a number ("order for 6824") and isn't part of it.
    private val GLUE = setOf("for", "to", "too", "tu", "fo", "fore")
    private val FOUR_SOUNDS = setOf("for", "fo", "fore")
    private val FOR_PREPOSITIONS = setOf("order", "orders", "receipt", "receipts", "number", "numbers",
        "phone", "telephone", "customer", "client", "alterations", "alteration", "work", "job", "look", "search")
    private val NUMBER_PROMPTS = FOR_PREPOSITIONS + setOf("find", "check", "sofia", "sophia", "digits")
    private val POLITE_ENDINGS = setOf("please", "thanks", "thank", "spasibo", "pozhaluysta")

    private val TEENS = mapOf(
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
        "desyat" to 10, "odinnadtsat" to 11, "dvenadtsat" to 12, "trinadtsat" to 13, "chetyrnadtsat" to 14,
        "pyatnadtsat" to 15, "shestnadtsat" to 16, "semnadtsat" to 17, "vosemnadtsat" to 18, "devyatnadtsat" to 19,
        "tvelv" to 12, "tvelf" to 12,
    )

    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fourty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90,
        "dvadtsat" to 20, "tridtsat" to 30, "sorok" to 40, "pyatdesyat" to 50, "shestdesyat" to 60,
        "semdesyat" to 70, "vosemdesyat" to 80, "devyanosto" to 90,
        // English tens written in Cyrillic by the Russian recognizer ("сёрти", "найнти")
        "tventi" to 20, "tuenti" to 20, "serti" to 30, "sorti" to 30, "fyorti" to 40, "forti" to 40, "ferti" to 40,
        "fifti" to 50, "siksti" to 60, "seventi" to 70, "eyti" to 80, "eiti" to 80, "naynti" to 90,
    )

    // RU hundreds are whole numbers; EN "hundred" multiplies.
    private val HUNDREDS = mapOf(
        "sto" to 100, "dvesti" to 200, "trista" to 300, "chetyresta" to 400, "pyatsot" to 500,
        "shestsot" to 600, "semsot" to 700, "vosemsot" to 800, "devyatsot" to 900,
    )
    private val MULT = mapOf("hundred" to HUNDRED, "thousand" to THOUSAND, "tysyacha" to THOUSAND, "tysyachi" to THOUSAND, "tysyach" to THOUSAND)

    private sealed class Tok {
        data class Digits(val s: String) : Tok()
        data class Unit(val v: Int, val soft: Boolean, val word: String = "") : Tok()
        data class Teen(val v: Int) : Tok()
        data class Tens(val v: Int) : Tok()
        data class Hundreds(val v: Int) : Tok()
        data class Mult(val m: Int) : Tok()
        data class Word(val w: String) : Tok()
    }

    private fun classify(w: String): Tok = when {
        w.all { it.isDigit() } -> Tok.Digits(w)
        w in UNITS -> Tok.Unit(UNITS.getValue(w), false)
        w in SOFT -> Tok.Unit(SOFT.getValue(w), true, w)
        w in TEENS -> Tok.Teen(TEENS.getValue(w))
        w in TENS -> Tok.Tens(TENS.getValue(w))
        w in HUNDREDS -> Tok.Hundreds(HUNDREDS.getValue(w))
        w in MULT -> Tok.Mult(MULT.getValue(w))
        else -> Tok.Word(w)
    }

    private fun isNumber(t: Tok) = t !is Tok.Word
    private fun isHard(t: Tok) = isNumber(t) && !(t is Tok.Unit && t.soft)

    /** Normalized text in, same text out with each run of number words replaced by digits. */
    fun toDigits(normalized: String): String {
        val toks = normalized.split(' ').filter { it.isNotEmpty() }.map(::classify)
        val out = mutableListOf<String>()
        var i = 0
        while (i < toks.size) {
            if (!isNumber(toks[i])) { out += (toks[i] as Tok.Word).w; i++; continue }
            var j = i
            while (j < toks.size && isNumber(toks[j])) j++
            val run = toks.subList(i, j)
            val before = (toks.getOrNull(i - 1) as? Tok.Word)?.w
            val after = (toks.getOrNull(j) as? Tok.Word)?.w
            val isolatedFour = run.size == 1 && (run[0] as? Tok.Unit)?.word in FOUR_SOUNDS &&
                (toks.size == 1 || (before in NUMBER_PROMPTS && after == null))
            if (run.none(::isHard) && run.size < 4 && !isolatedFour) {
                // Short soft runs are words unless "for" is an isolated dictated digit.
                run.forEach { out += softWord(it) }
            } else {
                out += render(run, before !in FOR_PREPOSITIONS, after == null || after in POLITE_ENDINGS)
            }
            i = j
        }
        return out.joinToString(" ")
    }

    /** Readable transcript: preserve original language/case outside recognized number-word runs. */
    fun display(text:String):String {
        val words=Regex("[\\p{L}\\p{N}]+").findAll(text).toList()
        val out=StringBuilder();var cursor=0;var i=0
        while(i<words.size) {
            if(!isNumber(classify(Lookup.normalize(words[i].value)))) { i++;continue }
            var j=i+1
            while(j<words.size && isNumber(classify(Lookup.normalize(words[j].value))))j++
            val start=words[i].range.first;val end=words[j-1].range.last+1
            val original=text.substring(start,end)
            val before = words.getOrNull(i - 1)?.value?.let(Lookup::normalize).orEmpty()
            val after = words.getOrNull(j)?.value?.let(Lookup::normalize).orEmpty()
            val framed = listOf(before, Lookup.normalize(original), after).filter { it.isNotBlank() }.joinToString(" ")
            var digits = toDigits(framed)
            if (before.isNotEmpty()) digits = digits.removePrefix("$before ")
            if (after.isNotEmpty()) digits = digits.removeSuffix(" $after")
            if(digits.any(Char::isDigit)) { out.append(text.substring(cursor,start));out.append(digits);cursor=end }
            i=j
        }
        return out.append(text.substring(cursor)).toString()
    }

    private fun softWord(t: Tok): String = (t as Tok.Unit).word

    private fun render(run: List<Tok>, leadingFourIsDigit: Boolean, trailingFourIsDigit: Boolean): List<String> {
        // Leading "for"/"to" usually belong to the sentence ("order for 6824"), unless
        // dropping them leaves fewer than 4 digits ("for eight three eight" = 4838).
        var start = 0
        while (start < run.size && (run[start] as? Tok.Unit)?.let { it.soft && it.word in GLUE } == true) {
            val word = (run[start] as Tok.Unit).word
            // Prefer 4 in a dictated sequence. A grammatical "order for …" consumes at most one preposition.
            if (word in FOUR_SOUNDS && (leadingFourIsDigit || start > 0 || run.size == 1)) break
            start++
        }
        var end=run.size
        // A trailing preposition after a complete number belongs to the sentence:
        // "receipt 12345 for Alex" must not become receipt 123454.
        while(end>start && (run[end-1] as? Tok.Unit)?.let { it.soft && it.word in GLUE }==true &&
            render2(run.subList(start,end-1)).joinToString("").length>=4) {
            if ((run[end-1] as Tok.Unit).word in FOUR_SOUNDS && trailingFourIsDigit) break
            end--
        }
        val trimmed = render2(run.subList(start, end))
        if (start > 0 && trimmed.joinToString("").length < 4) {
            val full = render2(run)
            if (full.joinToString("").length >= 4) return full
        }
        return run.subList(0, start).map(::softWord) + trimmed + run.subList(end,run.size).map(::softWord)
    }

    private fun render2(run: List<Tok>): List<String> {
        if (run.isEmpty()) return emptyList()
        if (run.any { it is Tok.Mult || it is Tok.Hundreds }) return listOf(arithmetic(run).toString())
        val out = mutableListOf<String>()
        var pendingDigits = StringBuilder()
        var digitParts = 0 // how many digit groups (as written, not words) are in pendingDigits
        fun flushDigits() { if (pendingDigits.isNotEmpty()) { out += pendingDigits.toString(); pendingDigits = StringBuilder() } }
        var k = 0
        while (k < run.size) {
            when (val t = run[k]) {
                is Tok.Digits -> {
                    // Separate 4/5-digit numbers stay separate ("1034 1203"); short groups join ("702 555 6824").
                    if (t.s.length == 4 || t.s.length == 5) {
                        val next = run.getOrNull(k + 1)
                        if (pendingDigits.isEmpty() && (next == null || (next is Tok.Digits && (next.s.length == 4 || next.s.length == 5)))) {
                            out += t.s; k++; continue
                        }
                    }
                    // A receipt said in two parts ("26 314", "262 79") is one 5-digit number: close it there,
                    // so "26 314 26 315" stays two receipts instead of one long number.
                    val before = pendingDigits.length
                    pendingDigits.append(t.s)
                    if (before in 2..3 && pendingDigits.length == 5 && digitParts == 1) { flushDigits(); digitParts = 0; k++; continue }
                    digitParts = if (before == 0) 1 else digitParts + 1
                }
                is Tok.Tens -> {
                    val next = run.getOrNull(k + 1)
                    if (next is Tok.Unit && next.v in 1..9) { pendingDigits.append(t.v + next.v); k++ }
                    else pendingDigits.append(t.v)
                    digitParts = 99
                }
                is Tok.Teen -> { pendingDigits.append(t.v); digitParts = 99 }
                is Tok.Unit -> { pendingDigits.append(t.v); digitParts = 99 }
                else -> {}
            }
            k++
        }
        flushDigits()
        return out
    }

    private fun arithmetic(run: List<Tok>): Int {
        var total = 0
        var current = 0
        for (t in run) when (t) {
            is Tok.Digits -> current += t.s.toIntOrNull() ?: 0
            is Tok.Unit -> current += t.v
            is Tok.Teen -> current += t.v
            is Tok.Tens -> current += t.v
            is Tok.Hundreds -> current += t.v
            is Tok.Mult -> if (t.m == HUNDRED) current = (if (current == 0) 1 else current) * 100
                else { total += (if (current == 0) 1 else current) * 1000; current = 0 }
            is Tok.Word -> {}
        }
        return total + current
    }
}
