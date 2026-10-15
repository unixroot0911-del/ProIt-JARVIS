package com.jarvis.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * Specialist roles. A role is not a separate model: it is the same brain with its own instructions, its own set of allowed
 * tools and its own kind of model. The coordinator only uses one when it really helps.
 */
object Agents {
    class Role(val name: String, val title: String, val prompt: String, val groups: Set<String>, val type: Router.T)

    val roles: Map<String, Role> = listOf(
        Role("researcher", "Research agent",
            "You find and check information. Search with several differently worded queries, prefer official and primary sources, open the pages that matter, " +
                "compare sources, note conflicts and dates, and never state as fact what you did not see. Keep a list of the URLs you used.",
            setOf("web", "kb", "files", "github", "memory"), Router.T.REASON),
        Role("coder", "Coding agent",
            "You build and repair software. Understand the requirement, choose the simplest structure, write complete files, run the tests, read the real error, " +
                "form a hypothesis, change ONE thing, run again, compare before and after, and undo a change that made things worse. Never edit many files at random.",
            setOf("code", "files", "github", "docs"), Router.T.CODE),
        Role("analyst", "Data agent",
            "You work with numbers. Read the data, find missing values and duplicates, clean it, calculate with code (never by guessing), compare periods, " +
                "draw charts, and separate computed facts from your interpretation. State the limits of the data.",
            setOf("data", "files", "docs", "code"), Router.T.REASON),
        Role("filer", "Files agent",
            "You create, read, organise and convert documents and files. Pick the right format, create the real file with a tool, check that it exists and has the content, and report its path.",
            setOf("files", "docs"), Router.T.REASON),
        Role("automator", "Automation agent",
            "You turn repeated work into automatic work: scripts, workflows, schedules and watchers. You test on sample data first and describe what will run before it is switched on.",
            setOf("automation", "files", "web", "code", "phone"), Router.T.CODE),
        Role("writer", "Content agent",
            "You write: reports, articles, posts, emails, plans, scripts, translations. Know the audience and the goal, choose the tone, organise the ideas, remove repetition, " +
                "keep every factual claim tied to the material you were given, and mark anything unverified.",
            setOf("docs", "files"), Router.T.WRITE),
        Role("memory", "Memory agent",
            "You store and retrieve what AXIOM knows: projects, preferences, documents and decisions. You search by meaning, show the source of every item, and delete what the user asks to delete.",
            setOf("kb", "memory"), Router.T.REASON),
        Role("planner", "Planning agent", "You turn goals into ordered, checkable steps.", emptySet(), Router.T.PLAN),
        Role("qa", "Quality agent", "You check results against the goal and the evidence, never against your own opinion.", emptySet(), Router.T.VERIFY)
    ).associateBy { it.name }

    fun role(name: String): Role = roles[name] ?: roles["researcher"]!!

    private fun style(): String {
        val lang = Config.get("language")
        val detail = Config.get("detail")
        return "\nLanguage: " + (if (lang == "auto") "reply in the language the user wrote in (Arabic, Darija, French or English)." else "reply in $lang.") +
            "\nDetail: " + when (detail) { "brief" -> "as short as possible."; "deep" -> "thorough, with reasoning and evidence."; else -> "clear and compact." }
    }

    /** A single piece of thinking or writing with a role, from material already collected. */
    suspend fun think(c: ToolCtx, roleName: String, instruction: String, material: String, avoid: String = ""): String {
        val r = role(roleName)
        val system = "You are the ${r.title} of AXIOM, a personal AI system on the user's phone.\n" + r.prompt + style() + "\n" + Shield.RULES +
            "\nWrite the result directly. Do not describe what you are going to do."
        val user = instruction + (if (material.isNotBlank()) "\n\nMATERIAL:\n$material" else "")
        val out = Router.run(c.brain, r.type, Router.Opts(system, user, task = c.task, avoidProvider = avoid))
        return out.trim()
    }

    private fun catalogFor(r: Role): String = Tools.describe(Tools.all().filter { it.group in r.groups || it.group == "core" }.map { it.name })

    /**
     * A small working loop for open-ended steps (research, debugging, analysis): the agent picks one tool at a time, sees the REAL result,
     * and adapts. Everything it reads from outside is wrapped as untrusted data. Bounded by steps and by repeated identical calls.
     */
    suspend fun work(c: ToolCtx, roleName: String, goal: String, context: String, maxSteps: Int): ToolResult {
        val r = role(roleName)
        val limit = maxSteps.coerceIn(2, minOf(Config.int("max_steps"), 14))
        val system = "You are the ${r.title} of AXIOM.\n" + r.prompt + style() + "\n" + Shield.RULES + "\n\n" +
            "You work in steps. Each turn answer with ONE JSON object and nothing else:\n" +
            "  {\"thought\":\"one short line\",\"tool\":\"tool_name\",\"args\":{...}}     to use a tool\n" +
            "  {\"final\":\"the complete result for the user\",\"files\":[\"paths of files you made\"]}     when the goal is reached\n" +
            "Rules: use only the listed tools; one call per turn; never repeat an identical call; if a tool fails, change the approach; read the real results before you conclude; " +
            "cite URLs for facts from the web; say clearly what you could not verify. You have at most $limit steps.\n\nTOOLS:\n" + catalogFor(r)
        val hist = ArrayList<Turn>()
        val files = LinkedHashSet<String>()
        val seen = HashSet<String>()
        var untrusted = false
        var user = "GOAL: $goal" + (if (context.isNotBlank()) "\n\nCONTEXT:\n$context" else "")
        var badJson = 0
        for (step in 1..limit) {
            if (c.cancelled()) return fail("Cancelled.")
            val raw = Router.run(c.brain, r.type, Router.Opts(system, user, hist.takeLast(14), json = true, task = c.task))
            val o = try { JSONObject(Brain.extractJson(raw)) } catch (e: Exception) { null }
            hist.add(Turn("user", user)); hist.add(Turn("model", Axiom.clip(raw, 1500)))
            if (o == null) {
                if (++badJson >= 3) return fail("The agent kept answering without valid JSON.", retriable = true)
                user = "Your answer was not a single JSON object. Answer again with ONLY the JSON."
                continue
            }
            if (o.has("final")) {
                val fa = o.optJSONArray("files") ?: JSONArray()
                for (i in 0 until fa.length()) files.add(fa.optString(i))
                return ToolResult(true, o.optString("final"), files.toList(), untrusted)
            }
            val tool = o.optString("tool")
            val args = o.optJSONObject("args") ?: JSONObject()
            if (tool.isEmpty()) { user = "Choose a tool or give the final answer."; continue }
            val sig = tool + args.toString()
            if (!seen.add(sig)) { user = "You already made exactly that call. Use its result, or try something different, or give the final answer."; continue }
            val allowed = Tools.get(tool)?.let { it.group in r.groups || it.group == "core" } == true
            val res = if (!allowed) fail("The tool \"$tool\" is not available to the ${r.title}.") else Tools.execute(c, tool, args)
            if (res.untrusted) untrusted = true
            res.files.forEach { files.add(it) }
            c.progress("${r.title}: step $step, $tool " + (if (res.ok) "ok" else "failed"))
            user = "RESULT of $tool (" + (if (res.ok) "ok" else "FAILED") + "):\n" + Axiom.clip(res.output, 6000) +
                (if (step >= limit - 1) "\n\nYou are nearly out of steps: give the final answer now with what you have." else "")
        }
        return ToolResult(false, "The agent used all $limit steps without finishing. Partial work may be saved in the project folder.", files.toList(), untrusted, retriable = false)
    }
}
