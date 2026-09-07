package io.github.ndndndn1.meg

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class PolicyTest {
    @Test
    fun sharedContracts() {
        val raw = javaClass.classLoader!!.getResource("policy-cases.json")!!.readText()
        val cases = JSONArray(raw)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val statuses = c.getJSONArray("statuses")
            var p = Policy()
            for (j in 0 until statuses.length()) p = p.next(statuses.getString(j))
            assertEquals(
                c.getString("name"),
                Policy(c.getString("mode"), c.getInt("unsupported"), c.getInt("calibrated")),
                p,
            )
        }
    }
}
