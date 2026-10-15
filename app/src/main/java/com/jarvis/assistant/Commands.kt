package com.jarvis.assistant

/**
 * Instant, rule-based commands for managing AXIOM itself (tasks, projects, usage, settings). No model call is spent on them.
 * Works with typed text and with Telegram slash commands (/stop_ab12c becomes "stop ab12c").
 */
object Commands {
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val tasks = rx("^(my )?(tasks|task list|المهام|مهامي|قائمة المهام)$")
    private val taskOne = rx("^(?:task|مهمة|المهمة|status|حالة)\\s+#?([\\w-]{3,})$")
    private val stop = rx("^(?:stop|cancel|أوقف|اوقف|وقف|ألغ|الغ)\\s+(?:task\\s+|مهمة\\s+)?#?([\\w-]{3,})$")
    private val pause = rx("^(?:pause|أوقف مؤقتا|اوقف مؤقتا|توقف مؤقتا)\\s+#?([\\w-]{3,})$")
    private val resume = rx("^(?:resume|continue task|استأنف|كمل المهمة)\\s+#?([\\w-]{3,})$")
    private val retry = rx("^(?:retry|أعد|اعد|اعد المحاولة|أعد المحاولة)\\s+#?([\\w-]{3,})$")
    private val run = rx("^(?:run|start|شغل|ابدأ|ابدا)\\s+(?:task\\s+|مهمة\\s+)#?([\\w-]{3,})$")
    private val projects = rx("^(projects|my projects|المشاريع|مشاريعي)$")
    private val projectStatus = rx("^(?:project|مشروع|المشروع)\\s+(.{2,40})$")
    private val projectNew = rx("^(?:new project|create project|مشروع جديد|أنشئ مشروع|انشئ مشروع)\\s+(.{2,40}?)(?:\\s*[:：-]\\s*(.+))?$")
    private val projectSwitch = rx("^(?:switch project|use project|activate project|غير المشروع|اشتغل على مشروع)\\s+(.{2,40})$")
    private val usage = rx("^(usage|quota|quotas|limits|الحصص|الاستهلاك|الحصة)$")
    private val audit = rx("^(audit|audit log|log|سجل التدقيق|السجل)$")
    private val settings = rx("^(settings list|config|الإعدادات المتقدمة|الاعدادات المتقدمة)$")
    private val setting = rx("^(?:set|اضبط)\\s+([a-z_]+)\\s+(?:to\\s+|=\\s*)?(.+)$")
    private val prio = rx("^(?:priority|أولوية|اولوية)\\s+#?([\\w-]{3,})\\s+([1-5])$")
    private val perf = rx("^(performance|stats|الأداء|الاداء|إحصائيات|احصائيات)$")
    private val prioritize = rx("^(?:prioritize|rank tasks|رتب المهام|رتب الأولويات)(?:\\s+(.{2,40}))?$")

    /** Returns the answer, or null when the text is not one of these commands. */
    suspend fun handle(raw: String, prefs: Prefs, brain: Brain): String? {
        var t = raw.trim().trimEnd('.', '!', '؟', '?')
        if (t.startsWith("/")) t = t.removePrefix("/").replace('_', ' ').trim()
        if (t.length > 80) return null
        taskOne.find(t)?.let { m -> val id = Tasks.resolve(m.groupValues[1]) ?: return "I could not find task ${m.groupValues[1]}."; return Tasks.detail(id) }
        stop.find(t)?.let { m -> val id = Tasks.resolve(m.groupValues[1]) ?: return "I could not find task ${m.groupValues[1]}."; return TaskEngine.stop(id) }
        pause.find(t)?.let { m -> val id = Tasks.resolve(m.groupValues[1]) ?: return "I could not find that task."; return TaskEngine.pause(id) }
        resume.find(t)?.let { m -> val id = Tasks.resolve(m.groupValues[1]) ?: return "I could not find that task."; return TaskEngine.resume(id) }
        retry.find(t)?.let { m -> val id = Tasks.resolve(m.groupValues[1]) ?: return "I could not find that task."; return TaskEngine.retry(id) }
        run.find(t)?.let { m -> val id = Tasks.resolve(m.groupValues[1]) ?: return "I could not find that task."; return TaskEngine.resume(id).also { Tasks.set(id, "status" to Tasks.RUNNING) } }
        prio.find(t)?.let { m -> return Projects.setPriority(m.groupValues[1], m.groupValues[2].toInt()) }
        if (tasks.matches(t)) {
            val active = Tasks.roots(Tasks.PLANNING, Tasks.RUNNING, Tasks.AWAITING, Tasks.PAUSED)
            val recent = Tasks.recentRoots(12).filter { it["id"] !in active.map { a -> a["id"] } }
            val sb = StringBuilder()
            if (active.isNotEmpty()) sb.append("Running now:\n").append(active.joinToString("\n") { Tasks.line(it) }).append("\n\n")
            if (recent.isNotEmpty()) sb.append("Recent:\n").append(recent.joinToString("\n") { Tasks.line(it) })
            val w = Approvals.list()
            if (w.isNotEmpty()) sb.append("\n\nWaiting for you:\n").append(w.joinToString("\n") { "• " + Axiom.clip(it.text, 100) })
            return sb.toString().trim().ifEmpty { "No tasks yet. Ask me for something big and I will plan it." }
        }
        if (projects.matches(t)) {
            val l = Projects.list()
            return if (l.isEmpty()) "No projects yet. Say: new project NAME: its goal." else "Projects (active: ${prefs.activeProject}):\n" + l.joinToString("\n") { "• " + it["name"] + " — " + Projects.progress(it["name"]!!) + "%  " + Axiom.clip(it["goal"]!!, 50) }
        }
        projectNew.find(t)?.let { m ->
            val name = m.groupValues[1].trim(); Projects.ensure(name, m.groupValues[2].trim()); prefs.activeProject = Projects.key(name)
            return "Project ${Projects.key(name)} created and active. Folder: ${Workspace.project(name).path}" + (if (m.groupValues[2].isNotBlank()) "\nSay \"plan project ${Projects.key(name)}\" and I will break it into phases." else "")
        }
        rx("^(?:plan project|خطط مشروع)\\s+(.{2,40})$").find(t)?.let { m -> return Projects.planPhases(brain, m.groupValues[1].trim(), "") }
        prioritize.find(t)?.let { m -> return Projects.prioritize(brain, m.groupValues[1].ifBlank { prefs.activeProject }.trim()) }
        projectSwitch.find(t)?.let { m -> return Projects.switch(prefs, m.groupValues[1].trim()) }
        projectStatus.find(t)?.let { m -> if (Projects.get(m.groupValues[1].trim()) != null) return Projects.status(m.groupValues[1].trim()) }
        if (usage.matches(t)) return Quota.report() + "\n\n" + Perf.summary("task", 6).joinToString("\n") { it["name"] + ": " + it["n"] + " runs, " + it["good"] + " ok, avg " + it["avg_ms"] + " ms" }
        if (audit.matches(t)) return Audit.format(20)
        if (perf.matches(t)) return Evaluate.report()
        if (settings.matches(t)) return Config.describe()
        setting.find(t)?.let { m -> if (Config.specs.any { it.key == m.groupValues[1] }) return Config.set(m.groupValues[1], m.groupValues[2]) ?: "Saved: ${m.groupValues[1]} = ${m.groupValues[2].trim()}" }
        return null
    }
}
