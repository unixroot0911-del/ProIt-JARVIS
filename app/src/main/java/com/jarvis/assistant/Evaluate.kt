package com.jarvis.assistant

/** Measured results only: success rates, times and retries per tool, role and model, taken from the performance log. */
object Evaluate {
    fun report(): String {
        val sb = StringBuilder("PERFORMANCE (real measurements)\n")
        for ((title, kind) in listOf("Tools" to "tool", "Task steps" to "task", "Models" to "model")) {
            val rows = Perf.summary(kind, 8)
            if (rows.isEmpty()) continue
            sb.append("\n").append(title).append(":\n")
            for (r in rows) {
                val n = r["n"]!!.toInt(); val good = r["good"]!!.toIntOrNull() ?: 0
                sb.append("• ").append(r["name"]).append(": ").append(n).append(" runs, ").append(good * 100 / maxOf(n, 1)).append("% ok, avg ").append(r["avg_ms"]).append(" ms")
                if ((r["avg_try"]?.toDoubleOrNull() ?: 1.0) > 1.2) sb.append(", ").append("%.1f".format(r["avg_try"]!!.toDouble())).append(" tries")
                sb.append('\n')
            }
        }
        return if (sb.length < 40) "No measurements yet. They build up as I work." else sb.toString().trim()
    }
}
