package com.bringtech.ft600

enum class ClearResult { OK, REFUSED, NONE }

data class MilInfo(val milOn: Boolean, val count: Int)

/** Funções puras para interpretar as respostas do ELM327 / OBD2. */
object Elm {

    private fun lines(resp: String): List<String> =
        resp.split('\r', '\n')
            .map { it.replace(" ", "").uppercase().removePrefix("SEARCHING...") }
            .filter { it.isNotEmpty() }

    /** Procura "prefix" (ex.: 410C) e devolve os bytes que vêm depois. */
    fun hexBytes(resp: String, prefix: String): IntArray? {
        for (l in lines(resp)) {
            val i = l.indexOf(prefix)
            if (i < 0) continue
            val hex = l.substring(i + prefix.length)
            val out = ArrayList<Int>()
            var k = 0
            while (k + 1 < hex.length) {
                val v = hex.substring(k, k + 2).toIntOrNull(16) ?: break
                out.add(v)
                k += 2
            }
            return out.toIntArray()
        }
        return null
    }

    fun milFromBytes(b: IntArray): MilInfo? {
        if (b.isEmpty()) return null
        return MilInfo((b[0] and 0x80) != 0, b[0] and 0x7F)
    }

    fun parseMil(resp: String): MilInfo? {
        val b = hexBytes(resp, "4101") ?: return null
        return milFromBytes(b)
    }

    /** Resposta do modo 03 (ler falhas). Em CAN o primeiro byte depois do 43 é a contagem. */
    fun parseDtcs(resp: String, isCan: Boolean): List<String> {
        val msgs = ArrayList<StringBuilder>()
        for (raw in lines(resp)) {
            // linha só com o tamanho (multi-frame), ex.: "00A"
            if (raw.length == 3 && raw.all { it in '0'..'9' || it in 'A'..'F' }) continue
            var l = raw
            var cont = false
            if (l.length >= 2 && l[1] == ':') {
                cont = l[0] != '0'
                l = l.substring(2)
            }
            if (cont && msgs.isNotEmpty()) {
                msgs.last().append(l)
            } else if (l.startsWith("43")) {
                msgs.add(StringBuilder(l))
            }
        }
        val out = LinkedHashSet<String>()
        for (m in msgs) {
            val hex = m.substring(2)
            val bytes = ArrayList<Int>()
            var k = 0
            while (k + 1 < hex.length) {
                val v = hex.substring(k, k + 2).toIntOrNull(16) ?: break
                bytes.add(v)
                k += 2
            }
            val data = if (isCan && bytes.isNotEmpty()) bytes.drop(1) else bytes
            var j = 0
            while (j + 1 < data.size) {
                val b1 = data[j]
                val b2 = data[j + 1]
                if (b1 != 0 || b2 != 0) out.add(fmt(b1, b2))
                j += 2
            }
        }
        return out.toList()
    }

    private fun fmt(b1: Int, b2: Int): String {
        val t = "PCBU"[(b1 shr 6) and 3]
        val d2 = (b1 shr 4) and 3
        val d3 = (b1 and 0xF).toString(16).uppercase()
        val rest = b2.toString(16).uppercase().padStart(2, '0')
        return "$t$d2$d3$rest"
    }

    /** Resposta do modo 04 (apagar falhas + luz do painel): positiva = 44, negativa = 7F 04 xx. */
    fun clearResult(resp: String): ClearResult {
        for (l in lines(resp)) {
            if (l.contains("7F04")) return ClearResult.REFUSED
            if (l.startsWith("44")) return ClearResult.OK
        }
        return ClearResult.NONE
    }
}
