package io.github.ndndndn1.meg

import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject

object Calculation {
    private const val N = "([+-]?\\d{1,12}(?:\\.\\d{1,8})?)"
    private val pattern =
        Regex(
            "^$N\\s*([+*/-])\\s*$N\\s*(?:은|는|=|equals)\\s*$N\\s*(?:입니다|이다|야|다)?$",
            RegexOption.IGNORE_CASE,
        )

    fun analyze(text: String): Pair<JSONArray, JSONArray>? {
        val normalized =
            text
                .trim()
                .replace(Regex("[。.!]$"), "")
                .replace("더하기", "+")
                .replace("빼기", "-")
                .replace(Regex("곱하기|×"), "*")
                .replace(Regex("나누기|÷"), "/")
        val m = pattern.matchEntire(normalized) ?: return null
        val a = BigDecimal(m.groupValues[1])
        val b = BigDecimal(m.groupValues[3])
        val expected = BigDecimal(m.groupValues[4])
        val op = m.groupValues[2]
        if (op == "/" && b.compareTo(BigDecimal.ZERO) == 0) return null
        val answer =
            when (op) {
                "+" -> a + b
                "-" -> a - b
                "*" -> a * b
                else -> a
            }
        val valid = answer.compareTo(if (op == "/") expected * b else expected) == 0
        val display =
            if (op == "/")
                try {
                    a.divide(b).stripTrailingZeros().toPlainString()
                } catch (_: ArithmeticException) {
                    "${a.toPlainString()}/${b.toPlainString()}"
                }
            else answer.stripTrailingZeros().toPlainString()
        val equation = "${m.groupValues[1]} $op ${m.groupValues[3]} = $display"
        val eid = id()
        val now = System.currentTimeMillis()
        val evidence =
            JSONObject()
                .put("id", eid)
                .put("text", equation)
                .put("title", "정확한 유리수 계산")
                .put("source", "내장 계산기")
                .put("version", "rational-v1")
                .put("basis", "CALCULATION")
                .put("checkedAt", now)
        val claim =
            JSONObject()
                .put("text", text)
                .put("status", if (valid) "VERIFIED" else "CONTRADICTED")
                .put("reason", equation)
                .put("basis", "CALCULATION")
                .put("evidenceIds", JSONArray().put(eid))
                .put(
                    "attempts",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("route", "CALCULATION")
                                .put("reason", "결정적 산술 검증")
                                .put("startedAt", now)
                                .put("durationMs", 0)
                                .put("outcome", "completed")
                        ),
                )
        return JSONArray().put(claim) to JSONArray().put(evidence)
    }
}
