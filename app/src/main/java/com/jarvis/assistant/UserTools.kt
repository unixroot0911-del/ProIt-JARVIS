package com.jarvis.assistant

import android.content.ContentValues
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool development: when AXIOM needs a small tool it does not have, it writes one (JavaScript, run in the network-less sandbox),
 * tests it against cases it wrote itself, repairs it until the tests pass, shows it to the user, and only then enables it.
 * Every version is kept so a bad change can be rolled back.
 */
object UserTools {
    private const val PREFIX = "ut_"

    private fun row(name: String) = Store.get().one("SELECT * FROM user_tools WHERE name = ?", name.removePrefix(PREFIX))

    class TestResult(val passed: Int, val total: Int, val failures: List<String>)

    suspend fun runTests(code: String, tests: JSONArray): TestResult {
        var passed = 0; val fails = ArrayList<String>()
        for (i in 0 until tests.length()) {
            val t = tests.optJSONObject(i) ?: continue
            val (ok, out) = JsSandbox.run(code, t.optString("input", ""), 8000)
            val eq = t.optString("expect_equals", ""); val has = t.optString("expect_contains", "")
            val good = ok && (if (eq.isNotEmpty()) out.trim().removePrefix("=> ").trim() == eq.trim() else if (has.isNotEmpty()) out.contains(has) else true)
            if (good) passed++ else fails.add("input ${Axiom.clip(t.optString("input"), 80)} -> got ${Axiom.clip(out, 120)}; expected " + (if (eq.isNotEmpty()) "exactly \"${Axiom.clip(eq, 80)}\"" else "to contain \"${Axiom.clip(has, 80)}\""))
        }
        return TestResult(passed, tests.length(), fails)
    }

    fun registerStored() {
        for (r in Store.get().query("SELECT * FROM user_tools WHERE enabled = 1 AND approved = 1")) registerOne(r["name"]!!, r["descr"]!!)
    }

    private fun registerOne(name: String, descr: String) {
        Tools.register(Tool(PREFIX + name, "$descr (a tool AXIOM built; input is text)", listOf(A("input", "the input text or JSON")), Risk.LOCAL, 30_000L, group = "usertools",
            run = { _, a ->
                val r = row(name) ?: return@Tool fail("This tool no longer exists.")
                val (good, out) = JsSandbox.run(r["code"]!!, a.getString("input"), 12000)
                Store.get().exec("UPDATE user_tools SET uses = uses + 1 WHERE name = ?", name)
                if (good) ok(out.ifBlank { "(no output)" }) else fail(out)
            }))
    }

    private fun saveVersion(name: String, code: String) {
        val v = (Store.get().scalar("SELECT COALESCE(MAX(version),0) FROM user_tool_versions WHERE name = ?", name) + 1).toInt()
        Store.get().replace("user_tool_versions", ContentValues().apply { put("name", name); put("version", v); put("code", code); put("ts", System.currentTimeMillis()) })
        Store.get().exec("UPDATE user_tools SET version = ? WHERE name = ?", v, name)
    }

    private suspend fun develop(c: ToolCtx, need: String, name0: String?, oldCode: String?, oldTests: JSONArray?): Pair<JSONObject?, String> {
        var feedback = ""; var best: JSONObject? = null; val log = StringBuilder()
        for (round in 1..4) {
            val j = try {
                Router.runJson(c.brain, Router.T.CODE, Router.Opts(
                    "You write small JavaScript tools that run in a sandbox with NO network and NO files. The code is a function BODY: it reads the string DATA (the input) and returns a string or a JSON-serialisable value. " +
                        "Return ONE JSON object: {\"name\":\"snake_case_name\",\"description\":\"one line: what it does and what input it expects\",\"code\":\"the function body\",\"tests\":[{\"input\":\"...\",\"expect_equals\":\"exact output\" or \"expect_contains\":\"text\"}]}. " +
                        "Write at least 4 tests, including an empty or malformed input and an edge case; expected values must be correct by hand calculation. Handle bad input by returning a clear message, never by throwing." +
                        (if (oldCode != null) "\nTHIS IS A CHANGE to existing code:\n$oldCode\nKeep it working for the old tests: ${oldTests}" else "") + feedback,
                    need + (if (name0 != null) "\nKeep the name: $name0" else ""), json = true, maxOut = 5000, task = c.task))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { return null to "the model could not write the tool: ${e.message}" }
            val code = j.optString("code"); val tests = j.optJSONArray("tests") ?: JSONArray()
            if (code.isBlank() || tests.length() < 3) { feedback = "\nYour last answer had no code or fewer than 3 tests. Fix that."; continue }
            val allTests = JSONArray(); for (i in 0 until tests.length()) allTests.put(tests.get(i)); oldTests?.let { for (i in 0 until it.length()) allTests.put(it.get(i)) }
            val r = runTests(code, allTests)
            log.append("round $round: ${r.passed}/${r.total} tests passed\n")
            if (r.passed == r.total) { j.put("tests", allTests); return j to log.toString() }
            best = best ?: j
            feedback = "\nYOUR PREVIOUS CODE:\n$code\nFAILED TESTS:\n" + r.failures.take(4).joinToString("\n") + "\nFix the code (or a wrong expected value) and return the full JSON again."
        }
        return null to (log.toString() + "It still failed its own tests after 4 attempts, so I did not enable it.")
    }

    fun register() {
        registerStored()
        Tools.register(Tool("tool_propose", "Build a new small tool for a need AXIOM has no tool for (parsing, converting, calculating, formatting text...). It is written in JavaScript, tested with its own test cases, repaired until they pass, shown to the user and enabled only after approval.",
            listOf(A("need", "what the tool must do, with an example input and output"), A("name", "short name", false)), Risk.LOCAL, 600_000L, group = "automation",
            run = { c, a ->
                val (j, log) = develop(c, a.getString("need"), a.optString("name", "").ifBlank { null }, null, null)
                if (j == null) return@Tool fail(log)
                val name = Workspace.slug(j.optString("name", "tool")).take(30).ifBlank { "tool" }
                val exists = row(name) != null
                val yes = c.confirm("Enable the new tool \"$PREFIX$name\"?\n${j.optString("description")}\n\n$log" + Axiom.clip(j.optString("code"), 700))
                if (exists) Store.get().delete("user_tools", "name = ?", name)
                Store.get().insert("user_tools", ContentValues().apply { put("name", name); put("descr", j.optString("description")); put("code", j.optString("code")); put("version", 1); put("enabled", if (yes) 1 else 0); put("tests", j.optJSONArray("tests").toString()); put("approved", if (yes) 1 else 0); put("created", System.currentTimeMillis()); put("uses", 0) })
                saveVersion(name, j.optString("code"))
                if (yes) { registerOne(name, j.optString("description")); ok("Tool $PREFIX$name is ready ($log). ${j.optString("description")}") } else ok("Saved as a draft, not enabled. $log")
            }))
        Tools.register(Tool("tool_update", "Change a tool AXIOM built. The new version must pass the old and the new tests; the old version stays available for rollback.",
            listOf(A("name", "tool name"), A("change", "what to change")), Risk.LOCAL, 600_000L, group = "automation",
            run = { c, a ->
                val r = row(a.getString("name")) ?: return@Tool fail("There is no tool called ${a.getString("name")}.")
                val (j, log) = develop(c, a.getString("change"), r["name"], r["code"], try { JSONArray(r["tests"]) } catch (e: Exception) { JSONArray() })
                if (j == null) return@Tool fail("$log The old version is unchanged.")
                if (!c.confirm("Use the new version of ${r["name"]}?\n$log" + Axiom.clip(j.optString("code"), 700))) return@Tool ok("Kept the old version.")
                Store.get().update("user_tools", ContentValues().apply { put("code", j.optString("code")); put("descr", j.optString("description").ifBlank { r["descr"] }); put("tests", j.optJSONArray("tests").toString()) }, "name = ?", r["name"]!!)
                saveVersion(r["name"]!!, j.optString("code")); registerOne(r["name"]!!, j.optString("description").ifBlank { r["descr"]!! })
                ok("Updated ${r["name"]} ($log).")
            }))
        Tools.register(Tool("tool_rollback", "Go back to an earlier version of a tool AXIOM built (see tool_list for versions).", listOf(A("name", "tool name"), A("version", "version number", false, "int")), Risk.LOCAL, group = "automation",
            run = { _, a ->
                val n = a.getString("name").removePrefix(PREFIX); val r = row(n) ?: return@Tool fail("There is no tool called $n.")
                val cur = r["version"]!!.toInt(); val target = if (a.has("version")) a.getInt("version") else cur - 1
                val v = Store.get().one("SELECT code FROM user_tool_versions WHERE name = ? AND version = ?", n, target.toString()) ?: return@Tool fail("There is no version $target.")
                Store.get().update("user_tools", ContentValues().apply { put("code", v["code"]) }, "name = ?", n); saveVersion(n, v["code"]!!)
                ok("Rolled $n back to version $target (saved as a new version).")
            }))
        Tools.register(Tool("tool_list", "List the tools AXIOM built, with version, uses and state.", emptyList(), Risk.READ, group = "automation",
            run = { _, _ -> ok(Store.get().query("SELECT * FROM user_tools ORDER BY created DESC").joinToString("\n") { "$PREFIX${it["name"]} v${it["version"]} ${if (it["enabled"] == "1") "on" else "off"} used ${it["uses"]}x — ${it["descr"]}" }.ifEmpty { "No custom tools yet." }) }))
        Tools.register(Tool("tool_delete", "Delete a tool AXIOM built. Always asks first.", listOf(A("name", "tool name")), Risk.DANGEROUS, group = "automation",
            run = { _, a -> val n = a.getString("name").removePrefix(PREFIX); Store.get().delete("user_tools", "name = ?", n); Store.get().delete("user_tool_versions", "name = ?", n); ok("Deleted $n (it disappears from the list after the app restarts).") }))

        Tools.register(Tool("script_make", "Write a reusable script file with a header (purpose, usage, inputs, outputs) and save it in the project's scripts folder. language = js (tested in the sandbox with the sample input), html, sh, py, bat, ps1. Non-JS scripts are saved but cannot be run here.",
            listOf(A("task", "what the script must do"), A("language", "js, html, sh, py, bat, ps1"), A("name", "file name without extension", false), A("sample", "sample input for a JS test run", false)), Risk.LOCAL, 360_000L, group = "code",
            run = { c, a ->
                val lang = a.getString("language").lowercase().removePrefix(".")
                if (lang !in listOf("js", "html", "sh", "py", "bat", "ps1")) return@Tool fail("language must be js, html, sh, py, bat or ps1.")
                var code = Router.run(c.brain, Router.T.CODE, Router.Opts("Write ONE complete, working $lang script for the task. Start with a comment header: purpose, usage, inputs, outputs, requirements. Handle errors and bad input clearly; no placeholders, no TODOs. Output only the code, no markdown fences.", a.getString("task"), maxOut = 6000, task = c.task))
                code = code.trim().removePrefix("```$lang").removePrefix("```javascript").removePrefix("```").removeSuffix("```").trim()
                var note = "Saved without running (a $lang script cannot be run inside the app)."
                if (lang == "js") {
                    val (ok, out) = JsSandbox.run("DATA=DATA; " + code, a.optString("sample", ""), 10000)
                    note = if (ok) "Ran in the sandbox with the sample input: " + Axiom.clip(out, 200) else "Warning: it failed in the sandbox: ${Axiom.clip(out, 200)}"
                }
                val f = Workspace.inside(c.project, "scripts/" + Workspace.slug(a.optString("name", "").ifBlank { a.getString("task") }) + "." + lang) ?: return@Tool fail("Cannot save there.")
                f.parentFile?.mkdirs(); f.writeText(code)
                if (!f.exists() || f.length() == 0L) fail("The script file was not written.") else ok("Script saved: ${f.path} (${code.lines().size} lines). $note", listOf(f.absolutePath))
            }))
    }
}
