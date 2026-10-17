package com.jarvis.assistant

import android.content.ContentValues
import android.graphics.Color

/** Skills: instructions AXIOM keeps and applies by itself when a request matches. AXIOM writes them, imports them (SKILL.md files, links), improves them, and rolls back. */
object Skills {
    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "skill" }
    private fun row(n: String) = Store.get().one("SELECT * FROM skills WHERE name = ?", slug(n))
    private fun toks(s: String) = Regex("[\\p{L}\\p{N}]{3,}").findAll(s.lowercase()).map { it.value }.toSet()

    fun save(name: String, descr: String, body: String, triggers: String, source: String): String {
        val n = slug(name)
        val v = (Store.get().scalar("SELECT COALESCE(MAX(version),0) FROM skill_versions WHERE name = ?", n) + 1).toInt()
        val now = System.currentTimeMillis()
        Store.get().replace("skill_versions", ContentValues().apply { put("name", n); put("version", v); put("descr", descr); put("body", body); put("ts", now) })
        val old = row(n)
        Store.get().replace("skills", ContentValues().apply {
            put("name", n); put("descr", descr.take(300)); put("body", body.take(30_000)); put("triggers", triggers.take(300)); put("version", v); put("enabled", 1)
            put("created", old?.get("created")?.toLongOrNull() ?: now); put("uses", old?.get("uses")?.toLongOrNull() ?: 0L); put("source", source)
        })
        return n
    }

    /** The skills that fit this request, as text to put in front of the model. */
    fun contextFor(text: String, max: Int = 2): String {
        val rows = try { Store.get().query("SELECT * FROM skills WHERE enabled = 1") } catch (e: Exception) { return "" }
        if (rows.isEmpty()) return ""
        val low = text.lowercase(); val t = toks(text)
        val scored = rows.map { r ->
            val trig = (r["triggers"] ?: "").split(',', '\n').map { it.trim().lowercase() }.filter { it.length >= 3 }
            val hit = trig.count { low.contains(it) }
            val sc = toks((r["name"] ?: "") + " " + (r["descr"] ?: "") + " " + (r["triggers"] ?: "")).intersect(t).size + hit * 3
            r to sc
        }.filter { it.second >= 3 }.sortedByDescending { it.second }.take(max)
        val sb = StringBuilder()
        if (scored.isNotEmpty()) {
            sb.append("ACTIVE SKILLS (written or imported by the user; follow them for this request):\n")
            for ((r, _) in scored) {
                sb.append("### ").append(r["name"]).append(": ").append(r["descr"]).append('\n').append(Axiom.clip(r["body"] ?: "", 5000)).append("\n\n")
                try { Store.get().exec("UPDATE skills SET uses = uses + 1 WHERE name = ?", r["name"]) } catch (e: Exception) { /* counter only */ }
            }
        }
        return sb.toString()
    }

    fun index(): String = try {
        Store.get().query("SELECT name, descr FROM skills WHERE enabled = 1 ORDER BY uses DESC LIMIT 40").joinToString("\n") { "- " + it["name"] + ": " + Axiom.clip(it["descr"] ?: "", 90) }
    } catch (e: Exception) { "" }

    private fun parseSkillMd(t: String): Triple<String, String, String> {
        var name = ""; var descr = ""; var body = t.trim()
        if (body.startsWith("---")) {
            val end = body.indexOf("\n---", 3)
            if (end > 0) {
                val fm = body.substring(3, end)
                Regex("(?m)^name:\\s*(.+)$").find(fm)?.let { name = it.groupValues[1].trim().trim('"', '\'') }
                Regex("(?m)^description:\\s*(.+)$").find(fm)?.let { descr = it.groupValues[1].trim().trim('"', '\'') }
                body = body.substring(end + 4).trim()
            }
        }
        if (name.isEmpty()) name = Regex("(?m)^#\\s+(.+)$").find(body)?.groupValues?.get(1) ?: "imported skill"
        if (descr.isEmpty()) descr = body.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.take(200) ?: name
        return Triple(name, descr, body)
    }

    fun register() {
        Tools.register(Tool("skill_create", "Save a skill: reusable instructions AXIOM applies by itself whenever a request matches. Give a clear name, a description saying WHEN it applies, optional trigger words (comma separated), and the full instructions.",
            listOf(A("name", "skill name"), A("description", "when to use it, one or two lines"), A("instructions", "the full instructions, steps, rules, examples"), A("triggers", "comma separated words or phrases that should activate it", false)), Risk.LOCAL, group = "skills", run = { _, a ->
                val n = save(a.getString("name"), a.getString("description"), a.getString("instructions"), a.optString("triggers", ""), "user")
                ok("Skill \"$n\" saved (version ${row(n)?.get("version")}). It is applied automatically when a request matches.")
            }))
        Tools.register(Tool("skill_make", "Write a skill for something the user wants AXIOM to do well from now on. AXIOM drafts it, reviews it for gaps, and saves it.", listOf(A("request", "what the skill should make AXIOM do, and how well")), Risk.LOCAL, 200_000L, group = "skills", run = { c, a ->
            val j = Router.runJson(c.brain, Router.T.WRITE, Router.Opts("You write skills: reusable instructions for an AI assistant. Return ONE JSON object {\"name\":\"kebab-case\",\"description\":\"when this applies, 1-2 lines\",\"triggers\":\"comma separated words\",\"instructions\":\"complete, concrete, step by step instructions with rules, quality criteria, pitfalls and a short example\"}. Specific and professional, no filler.", a.getString("request"), json = true, maxOut = 3000, task = c.task))
            val review = Router.run(c.brain, Router.T.VERIFY, Router.Opts("Review these skill instructions for ambiguity, missing steps, missing quality checks and anything that would make an assistant produce a mediocre result. Return the IMPROVED instructions only, complete, plain text.", j.optString("instructions"), maxOut = 3000, task = c.task))
            val instr = if (review.length > 200) review.trim() else j.optString("instructions")
            val n = save(j.optString("name", "skill"), j.optString("description", ""), instr, j.optString("triggers", ""), "made")
            ok("Skill \"$n\" written and saved.\nWhen: ${j.optString("description")}\n\n" + Axiom.clip(instr, 1500))
        }))
        Tools.register(Tool("skill_import", "Import a skill from a link (a SKILL.md or any markdown/text file, a GitHub file link) or from a file on the phone.", listOf(A("source", "https link or a file path"), A("name", "name override", false)), Risk.LOCAL, 90_000L, group = "skills", run = { c, a ->
            val src = a.getString("source").trim()
            val text: String = if (src.startsWith("http")) {
                val u = src.replace("github.com/", "raw.githubusercontent.com/").replace("/blob/", "/")
                Net.unsafe(u)?.let { return@Tool fail("Not allowed: $it") }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Net.get(u, maxBytes = 400_000).body }
            } else {
                val f = Workspace.readable(src, c.project) ?: return@Tool fail("I cannot read that path.")
                f.readText()
            }
            val (n0, d, body) = parseSkillMd(text)
            val n = save(a.optString("name", "").ifBlank { n0 }, d, body, "", src)
            ok("Imported skill \"$n\": $d", untrusted = true)
        }))
        Tools.register(Tool("skill_update", "Change a skill (instructions, description or triggers). The old version is kept.", listOf(A("name", "skill name"), A("instructions", "new full instructions", false), A("description", "new description", false), A("triggers", "new triggers", false)), Risk.LOCAL, group = "skills", run = { _, a ->
            val r = row(a.getString("name")) ?: return@Tool fail("No such skill.")
            save(r["name"]!!, a.optString("description", r["descr"] ?: ""), a.optString("instructions", r["body"] ?: ""), a.optString("triggers", r["triggers"] ?: ""), r["source"] ?: "")
            ok("Skill ${r["name"]} updated to version ${row(r["name"]!!)?.get("version")}.")
        }))
        Tools.register(Tool("skill_list", "List saved skills.", emptyList(), Risk.READ, group = "skills", run = { _, _ ->
            val rs = Store.get().query("SELECT * FROM skills ORDER BY name")
            if (rs.isEmpty()) ok("No skills yet.") else ok(rs.joinToString("\n") { it["name"] + " v" + it["version"] + (if (it["enabled"] != "1") " (off)" else "") + ": " + Axiom.clip(it["descr"] ?: "", 100) })
        }))
        Tools.register(Tool("skill_show", "Show a skill's full instructions.", listOf(A("name", "skill name")), Risk.READ, group = "skills", run = { _, a ->
            val r = row(a.getString("name")) ?: return@Tool fail("No such skill.")
            ok(r["name"] + " v" + r["version"] + "\n" + r["descr"] + "\ntriggers: " + r["triggers"] + "\n\n" + r["body"])
        }))
        Tools.register(Tool("skill_toggle", "Turn a skill on or off.", listOf(A("name", "skill name"), A("enabled", "true or false", true, "bool")), Risk.LOCAL, group = "skills", run = { _, a ->
            Store.get().exec("UPDATE skills SET enabled = ? WHERE name = ?", if (a.optBoolean("enabled", true)) 1 else 0, slug(a.getString("name"))); ok("Done.")
        }))
        Tools.register(Tool("skill_rollback", "Go back to an earlier version of a skill.", listOf(A("name", "skill name"), A("version", "version number", true, "int")), Risk.LOCAL, group = "skills", run = { _, a ->
            val n = slug(a.getString("name"))
            val v = Store.get().one("SELECT * FROM skill_versions WHERE name = ? AND version = ?", n, a.getLong("version").toString()) ?: return@Tool fail("No such version.")
            Store.get().exec("UPDATE skills SET descr = ?, body = ?, version = ? WHERE name = ?", v["descr"], v["body"], a.getLong("version"), n)
            ok("Skill $n is now version ${a.getLong("version")}.")
        }))
        Tools.register(Tool("skill_delete", "Delete a skill (always asks).", listOf(A("name", "skill name")), Risk.DANGEROUS, group = "skills", run = { c, a ->
            val r = row(a.getString("name")) ?: return@Tool fail("No such skill.")
            if (!c.confirm("Delete the skill \"${r["name"]}\"?")) return@Tool ToolResult(false, "Declined.", declined = true)
            Store.get().delete("skills", "name = ?", r["name"]!!); ok("Deleted.")
        }))
    }
}

/** The look of AXIOM's own screens, changeable by AXIOM (or by a mod) on request. */
object UiTheme {
    private fun sp() = Axiom.ctx.getSharedPreferences("axiom_ui", android.content.Context.MODE_PRIVATE)
    private fun col(k: String, d: String): Int = try { Color.parseColor(sp().getString(k, d)) } catch (e: Exception) { Color.parseColor(d) }
    val accent get() = col("accent", "#2AA8FF")
    val background get() = col("bg", "#05080F")
    val userBubble get() = col("user", "#1B4F8A")
    val aiBubble get() = col("ai", "#0E1B2B")
    val aiText get() = col("aitext", "#CFE8FF")
    val textSize get() = sp().getFloat("size", 15f)

    fun register() {
        Tools.register(Tool("ui_theme", "Change how AXIOM's chat screen looks. Colors are #RRGGBB. Only the given values change.",
            listOf(A("accent", "accent color", false), A("background", "screen background", false), A("user_bubble", "your message bubbles", false), A("ai_bubble", "AXIOM's bubbles", false), A("text_color", "AXIOM's text color", false), A("text_size", "text size 11-24", false, "number")), Risk.LOCAL, group = "skills", run = { _, a ->
                val e = sp().edit(); val bad = ArrayList<String>()
                for ((arg, key) in listOf("accent" to "accent", "background" to "bg", "user_bubble" to "user", "ai_bubble" to "ai", "text_color" to "aitext")) {
                    val v = a.optString(arg, ""); if (v.isBlank()) continue
                    try { Color.parseColor(v); e.putString(key, v) } catch (x: Exception) { bad.add(arg) }
                }
                if (a.has("text_size")) e.putFloat("size", a.optDouble("text_size", 15.0).toFloat().coerceIn(11f, 24f))
                e.apply()
                ok("The look is updated; it shows next time the chat opens." + (if (bad.isNotEmpty()) " (Not a valid color: ${bad.joinToString()}.)" else ""))
            }))
        Tools.register(Tool("ui_theme_reset", "Return the chat screen to the default look.", emptyList(), Risk.LOCAL, group = "skills", run = { _, _ -> sp().edit().clear().apply(); ok("Back to the default look.") }))
    }
}
