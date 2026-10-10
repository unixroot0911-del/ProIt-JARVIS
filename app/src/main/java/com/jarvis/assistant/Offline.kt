package com.jarvis.assistant

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Instant commands that need no internet and no AI quota: Arabic, Darija and English.
 * Anything that does not match a strict pattern returns null and goes to the brain.
 */
object Offline {

    private fun arabic(s: String) = s.any { it in '؀'..'ۿ' }

    private fun d(text: String, en: String, ar: String, type: String? = null, arg: String? = null, speak: Boolean = false) =
        Decision(if (arabic(text)) ar else en, type, arg, null, failed = false, speakResult = speak)

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val torch = rx("(كشاف|مصباح|فلاش|flash ?light|torch)")
    private val torchOff = rx("(طفي|طفّي|اطفئ|أطفئ|سد|اغلق|أغلق|off|stop)")
    private val timeQ = rx("^(كم الساعة|شحال فالساعة|شحال ف الساعة|الساعة شحال|what time is it|what's the time|time)$")
    private val batteryQ = rx("^(البطارية|كم البطارية|شحال فالبطارية|شحال ف البطارية|battery|battery level|battery status)$")
    private val volUp = rx("^((ارفع|زيد|علي|علّي|raise|increase|turn up).{0,12}(الصوت|volume)|volume up|الصوت (فوق|اعلى|أعلى))$")
    private val volDown = rx("^((اخفض|نقص|وطي|وطّي|lower|decrease|turn down).{0,12}(الصوت|volume)|volume down|الصوت (تحت|اقل|أقل))$")
    private val mute = rx("^(كتم|اكتم|اكتم الصوت|mute|silence)$")
    private val pause = rx("^(pause|pause music|stop music|وقف|وقف الموسيقى|وقف المزيكا|توقف|أوقف الموسيقى)$")
    private val play = rx("^(play|resume|play music|كمل|شغل الموسيقى|شغل المزيكا)$")
    private val next = rx("^(next|skip|next song|التالي|الموالية|الأغنية الموالية|الاغنية الموالية)$")
    private val prev = rx("^(previous|previous song|السابق|الأغنية السابقة|الاغنية السابقة)$")
    private val stopAgent = rx("^(stop agent|stop the agent|وقف الوكيل|أوقف الوكيل|اوقف الوكيل)$")
    private val timer = rx("^(?:set (?:a )?)?(?:timer|مؤقت|تايمر)(?: for| ل| ديال)? ?(\\d{1,4}) ?(seconds?|secs?|ثانية|ثواني|ثانيه|minutes?|mins?|دقيقة|دقائق|دقايق|دقيقه|hours?|ساعة|ساعات|ساعه)$")
    private val alarm = rx("^(?:set (?:an? )?alarm(?: for| at)?|wake me(?: up)? at|منبه|نبهني|فيقني)\\s*(?:على|ف|في|at)?\\s*(\\d{1,2})(?::(\\d{2}))?$")
    private val open = rx("^(?:open|launch|start|افتح|حل|شغل)\\s+(.{2,25})$")
    private val converseOn = rx("^(conversation mode|talk to me|let'?s talk|hands.?free( mode)?|وضع المحادثة|كلمني|تكلم معي|هضر معايا)$")
    private val converseOff = rx("^(stop conversation|end conversation|conversation off|that'?s all|خلاص|كفى|بس|وقف المحادثة|سالينا)$")
    private val watchOn = rx("^(watch me|watch my screen|watch the screen|راقبني|راقب شاشتي|شوفني)$")
    private val watchOff = rx("^(stop watching|watch off|stop watch mode|وقف المراقبة|كفى مراقبة|بطل تراقبني)$")
    private val call = rx("^(?:call|اتصل ب|اتصل بـ|اتصل|عيط ل|عيط على|كلم)\\s+(.{2,25})$")

    fun parse(raw: String, actions: Actions): Decision? {
        val t = raw.trim().trimEnd('.', '!', '?', '؟', '،').replace(Regex("\\s+"), " ")
        if (t.isEmpty() || t.length > 60) return null

        if (stopAgent.matches(t)) return d(t, "Stopping the agent.", "تم إيقاف الوكيل.", "stop_agent", "")

        if (watchOff.matches(t)) return d(t, "Watch mode off.", "تم إيقاف المراقبة.", "watch_stop", "")
        if (watchOn.matches(t)) return d(t, "Watching your screen.", "أراقب شاشتك الآن.", "watch_start",
            "Coach me: comment only when you notice something genuinely useful (a danger, a mistake, a better move, an answer)")
        if (converseOff.matches(t)) return d(t, "Conversation mode off.", "انتهى وضع المحادثة.", "converse", "off")
        if (converseOn.matches(t)) return d(t, "Conversation mode on.", "وضع المحادثة شغال.", "converse", "on")

        if (torch.containsMatchIn(t) && t.split(" ").size <= 5) {
            val off = torchOff.containsMatchIn(t)
            return d(t, if (off) "Flashlight off." else "Flashlight on.", if (off) "تم إطفاء الكشاف." else "تم تشغيل الكشاف.",
                "flashlight", if (off) "off" else "on")
        }
        if (timeQ.matches(t)) {
            val now = SimpleDateFormat("HH:mm", Locale.US).format(Date())
            return d(t, "It is $now.", "الساعة الآن $now.")
        }
        if (batteryQ.matches(t)) return d(t, "Checking.", "لحظة.", "battery", "", speak = true)
        if (mute.matches(t)) return d(t, "Muted.", "تم كتم الصوت.", "volume", "mute")
        if (volUp.matches(t)) return d(t, "Volume up.", "تم رفع الصوت.", "volume", "up")
        if (volDown.matches(t)) return d(t, "Volume down.", "تم خفض الصوت.", "volume", "down")
        if (pause.matches(t)) return d(t, "Paused.", "تم الإيقاف.", "media", "pause")
        if (play.matches(t)) return d(t, "Playing.", "تم التشغيل.", "media", "play")
        if (next.matches(t)) return d(t, "Next.", "التالي.", "media", "next")
        if (prev.matches(t)) return d(t, "Previous.", "السابق.", "media", "previous")

        timer.find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return null
            val unit = m.groupValues[2].lowercase()
            val secs = when {
                unit.startsWith("sec") || unit.startsWith("ثان") -> n
                unit.startsWith("min") || unit.startsWith("دق") -> n * 60
                else -> n * 3600
            }
            return d(t, "Timer set.", "تم ضبط المؤقت.", "set_timer", secs.toString())
        }
        alarm.find(t)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return null
            val min = m.groupValues[2].toIntOrNull() ?: 0
            if (h !in 0..23 || min !in 0..59) return null
            return d(t, "Alarm set.", "تم ضبط المنبه.", "set_alarm", "%02d:%02d".format(h, min))
        }
        call.find(t)?.let { m -> return d(t, "Calling.", "جاري الاتصال.", "call_contact", m.groupValues[1].trim()) }
        open.find(t)?.let { m ->
            val name = m.groupValues[1].trim()
            if (actions.findApp(name) != null || actions.settingFor(name) != null) return d(t, "Opening $name.", "جاري فتح $name.", "open_app", name)
        }
        return null
    }
}
