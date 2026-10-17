package com.jarvis.assistant

import org.json.JSONObject

/** Tools for the phone itself (messages, calls, alarms, apps...), for asking the user, and for projects and tasks. */
object CoreTools {
    private val failure = Regex("^(I could not|I cannot|I can't|Cannot|Could not|Invalid|Action '|I need|I found nothing|Failed|There is no|Permission|Not |No app|\"[^\"]+\" is not installed)", RegexOption.IGNORE_CASE)

    private suspend fun act(c: ToolCtx, type: String, arg: String): ToolResult {
        val r = Assistant.shared().runAction(type, arg, c.confirm, c.progress)?.trim().orEmpty().ifEmpty { "Done." }
        return if (failure.containsMatchIn(r)) fail(r) else ok(r)
    }

    fun register() {
        Tools.register(Tool("ask_user", "Ask the user one question (with 2-4 short answers) and wait for the answer. Use only when a detail is truly missing.",
            listOf(A("question", "the question"), A("options", "comma separated short answers", false)), Risk.READ, 25 * 60_000L, group = "core",
            run = { c, a ->
                val opts = a.optString("options", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(4)
                val ans = Approvals.ask(a.getString("question"), if (opts.isEmpty()) listOf("ok") else opts, c.task)
                if (ans.isEmpty()) fail("The user did not answer.") else ok(ans)
            }))

        Tools.register(Tool("notify_me", "Show the user a notification.", listOf(A("title", "short title"), A("text", "message")), Risk.LOCAL, group = "core",
            run = { _, a -> Notify.post("info", "me:" + a.getString("title"), a.getString("title"), a.getString("text"), 5_000L); ok("Notification shown.") }))

        Tools.register(Tool("time_now", "The current date, time and weekday.", emptyList(), Risk.READ, group = "core",
            run = { _, _ -> ok(java.text.SimpleDateFormat("EEEE yyyy-MM-dd HH:mm", java.util.Locale.ENGLISH).format(java.util.Date())) }))

        Tools.register(Tool("phone_context", "What the phone knows now: time, battery, reminders, calendar today, recent notifications.", emptyList(), Risk.READ, group = "phone",
            run = { _, _ -> val txt = Assistant.shared().buildContext(); ok(Shield.wrap("phone notifications and calendar", txt, 6000).text, untrusted = true) }))

        Tools.register(Tool("phone_message", "Send a WhatsApp or SMS message to a contact or number.",
            listOf(A("app", "whatsapp or sms"), A("to", "contact name or number"), A("text", "the exact message")), Risk.EXTERNAL, group = "phone",
            run = { c, a -> act(c, "send_message", a.getString("app") + "|" + a.getString("to") + "|" + a.getString("text")) }))
        Tools.register(Tool("phone_call", "Call a contact.", listOf(A("who", "contact name or number")), Risk.EXTERNAL, group = "phone", run = { c, a -> act(c, "call_contact", a.getString("who")) }))
        Tools.register(Tool("open_app", "Open an installed app or a system settings screen.", listOf(A("name", "app name")), Risk.LOCAL, group = "phone", run = { c, a -> act(c, "open_app", a.getString("name")) }))
        Tools.register(Tool("open_url", "Open a web address in the browser.", listOf(A("url", "address")), Risk.LOCAL, group = "phone", run = { c, a -> act(c, "open_url", a.getString("url")) }))
        Tools.register(Tool("set_alarm", "Set one of AXIOM's own alarms.", listOf(A("time", "HH:MM"), A("label", "text", false), A("repeat", "daily or weekly", false)), Risk.LOCAL, group = "phone",
            run = { c, a -> act(c, "set_alarm", a.getString("time") + "|" + a.optString("label", "") + "|" + a.optString("repeat", "")) }))
        Tools.register(Tool("set_timer", "Start a timer.", listOf(A("seconds", "length in seconds", true, "int")), Risk.LOCAL, group = "phone", run = { c, a -> act(c, "set_timer", a.getLong("seconds").toString()) }))
        Tools.register(Tool("remind", "Remind the user later. when = minutes from now (a number), 'HH:MM' or 'yyyy-MM-dd HH:mm'.",
            listOf(A("when", "minutes from now, HH:MM, or yyyy-MM-dd HH:mm"), A("text", "what to remind"), A("repeat", "daily or weekly", false)), Risk.LOCAL, group = "phone",
            run = { c, a ->
                val w = a.getString("when").trim()
                act(c, if (w.all { it.isDigit() }) "remind" else "remind_at", w + "|" + a.getString("text") + "|" + a.optString("repeat", ""))
            }))
        Tools.register(Tool("calendar_add", "Add an event to the phone calendar.", listOf(A("title", "event title"), A("start", "yyyy-MM-dd HH:mm"), A("minutes", "length, default 60", false, "int")), Risk.LOCAL, group = "phone",
            run = { c, a -> act(c, "calendar_add", a.getString("title") + "|" + a.getString("start") + "|" + a.optInt("minutes", 60)) }))
        Tools.register(Tool("device_control", "Flashlight, volume or media. what = flashlight | volume | media; value = on/off, up/down/mute/0-100, play/pause/next/previous.",
            listOf(A("what", "flashlight, volume or media"), A("value", "the setting")), Risk.LOCAL, group = "phone",
            run = { c, a -> act(c, a.getString("what"), a.getString("value")) }))
        Tools.register(Tool("download", "Find a free, legal download on the web and save it to the Downloads folder.", listOf(A("what", "what to download, or a direct link")), Risk.LOCAL, 240_000L, group = "phone",
            run = { c, a -> act(c, "download", a.getString("what")) }))
        Tools.register(Tool("build_app", "Build a complete, professional game, website or web app as a self-contained page and open it. Pass real details: audience, sections, language, style, features.", listOf(A("name", "app name"), A("description", "what it is and does: audience, sections, language, style, features", false)), Risk.LOCAL, 1_200_000L, group = "code",
            run = { c, a -> act(c, "build_app", a.getString("name") + "|" + a.optString("description", "")) }))
        Tools.register(Tool("phone_delete", "Delete a file, a built app or an installed app on the phone. Always asks the user first.", listOf(A("name", "what to delete")), Risk.LOCAL, 300_000L, group = "phone",
            run = { c, a -> act(c, "delete_file", a.getString("name")) }))
        Tools.register(Tool("memory_remember", "File a lasting fact (person, topic, preference, project) in AXIOM's sectioned memory.",
            listOf(A("kind", "person, topic, app, game or preference"), A("name", "who or what it is about"), A("key", "short label"), A("value", "the fact")), Risk.LOCAL, group = "memory",
            run = { c, a -> act(c, "remember", a.getString("kind") + "|" + a.getString("name") + "|" + a.getString("key") + "|" + a.getString("value")) }))
        Tools.register(Tool("memory_forget", "Delete what is filed about a name. Always asks the user first.", listOf(A("name", "name"), A("key", "one label only", false)), Risk.DANGEROUS, group = "memory",
            run = { c, a -> act(c, "forget_about", a.getString("name") + "|" + a.optString("key", "")) }))

        // ---- projects and tasks ----
        Tools.register(Tool("project_create", "Create a project (its own folder, memory and task list) and make it the active one.",
            listOf(A("name", "project name"), A("goal", "what it should achieve", false), A("description", "details", false)), Risk.LOCAL, group = "project",
            run = { c, a -> Projects.ensure(a.getString("name"), a.optString("goal", ""), a.optString("description", "")); c.prefs.activeProject = Projects.key(a.getString("name")); ok("Project ${Projects.key(a.getString("name"))} is ready and active. Folder: ${Workspace.project(a.getString("name")).path}") }))
        Tools.register(Tool("project_switch", "Make a project the active one.", listOf(A("name", "project name")), Risk.LOCAL, group = "project", run = { c, a -> ok(Projects.switch(c.prefs, a.getString("name"))) }))
        Tools.register(Tool("project_status", "Report on a project: progress, tasks, overdue items, decisions, problems, next step.", listOf(A("name", "project name, default the active one", false)), Risk.READ, group = "project",
            run = { c, a -> ok(Projects.status(a.optString("name", c.prefs.activeProject))) }))
        Tools.register(Tool("project_update", "Change a project field: goal, descr, requirements, risks, resources, budget, status, next_step. Use project_log for decisions and problems.",
            listOf(A("name", "project"), A("field", "field name"), A("value", "new value")), Risk.LOCAL, group = "project",
            run = { _, a -> Projects.update(a.getString("name"), a.getString("field"), a.getString("value"))?.let { fail(it) } ?: ok("Updated.") }))
        Tools.register(Tool("project_log", "Record a decision or a problem in a project.", listOf(A("name", "project"), A("kind", "decisions or problems"), A("text", "what happened")), Risk.LOCAL, group = "project",
            run = { _, a -> val k = a.getString("kind").lowercase(); if (k != "decisions" && k != "problems") fail("kind must be decisions or problems") else { Projects.log(a.getString("name"), k, a.getString("text")); ok("Recorded.") } }))
        Tools.register(Tool("project_plan", "Break a project's goal into phases and add them as waiting tasks.", listOf(A("name", "project"), A("goal", "goal, if not set yet", false)), Risk.LOCAL, 120_000L, group = "project",
            run = { c, a -> ok(Projects.planPhases(c.brain, a.getString("name"), a.optString("goal", ""))) }))
        Tools.register(Tool("project_prioritize", "Rank a project's waiting tasks by importance, impact, urgency, effort, cost, dependencies, risk and fit with the goal.", listOf(A("name", "project, default the active one", false)), Risk.LOCAL, 120_000L, group = "project",
            run = { c, a -> ok(Projects.prioritize(c.brain, a.optString("name", c.prefs.activeProject))) }))
        Tools.register(Tool("task_list", "List recent and running tasks.", emptyList(), Risk.READ, group = "project",
            run = { _, _ -> ok(Tasks.recentRoots(15).joinToString("\n") { Tasks.line(it) }.ifEmpty { "No tasks yet." }) }))
    }
}
