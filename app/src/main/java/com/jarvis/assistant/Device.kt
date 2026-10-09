package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.LocationManager
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Direct control of the phone: contacts, SMS, calendar, media, volume, flashlight, battery, location. */
class Device(private val ctx: Context) {

    companion object {
        val TYPES = setOf(
            "call_contact", "send_sms", "calendar_add", "media", "volume", "flashlight", "battery", "where_am_i", "whatsapp"
        )
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    private fun need(p: String, what: String) {
        if (!has(p)) throw IllegalStateException("$what permission is missing. Open Settings and press 'Grant phone permissions'.")
    }

    fun run(type: String, arg: String): String {
        return try {
            when (type.lowercase()) {
                "call_contact" -> callContact(arg)
                "send_sms" -> sendSms(arg)
                "whatsapp" -> { val p = arg.split("|", limit = 2); whatsapp(p.getOrElse(0) { "" }, p.getOrElse(1) { "" }) }
                "calendar_add" -> calendarAdd(arg)
                "media" -> media(arg)
                "volume" -> volume(arg)
                "flashlight" -> flashlight(arg)
                "battery" -> "Battery ${batteryLine()}."
                "where_am_i" -> whereAmI()
                else -> "Unsupported device action '$type'."
            }
        } catch (e: Exception) {
            e.message ?: "Device action failed."
        }
    }

    // ---- contacts, calls, SMS, WhatsApp ----

    private fun norm(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase()) {
            when {
                ch in '\u064B'..'\u065F' || ch == '\u0670' || ch == '\u0640' -> {}
                ch == 'أ' || ch == 'إ' || ch == 'آ' -> sb.append('ا')
                ch == 'ى' -> sb.append('ي')
                ch == 'ة' -> sb.append('ه')
                ch.isLetterOrDigit() || ch == ' ' -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private class Row(val name: String, val norm: String, val number: String, val mobile: Boolean)

    /** Finds a contact by name (tolerant of case, Arabic spelling variants and small typos) or accepts a raw number. */
    private fun contact(query: String): Pair<String, String> {
        val q = query.trim()
        if (q.count { it.isDigit() } >= 5) return q to q.filter { it.isDigit() || it == '+' }
        need(Manifest.permission.READ_CONTACTS, "Contacts")

        val rows = ArrayList<Row>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
            ), null, null, null
        )?.use {
            while (it.moveToNext() && rows.size < 6000) {
                val name = it.getString(0) ?: continue
                val number = it.getString(1) ?: continue
                rows.add(Row(name, norm(name), number, it.getInt(2) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE))
            }
        }
        if (rows.isEmpty()) throw IllegalStateException("I cannot see any contacts. Allow Contacts in Settings, Phone permissions.")

        val nq = norm(q)
        fun pick(list: List<Row>): Pair<String, String>? {
            if (list.isEmpty()) return null
            val first = list.first().name
            val same = list.filter { it.name == first }
            val r = same.firstOrNull { it.mobile } ?: same.first()
            return r.name to r.number
        }
        pick(rows.filter { it.norm == nq })?.let { return it }
        pick(rows.filter { it.norm.startsWith(nq) })?.let { return it }
        pick(rows.filter { it.norm.contains(nq) })?.let { return it }
        pick(rows.filter { r -> r.norm.split(" ").any { t -> t == nq || (nq.length >= 3 && t.startsWith(nq)) } })?.let { return it }

        val scored = rows.map { r -> r to (listOf(lev(r.norm, nq)) + r.norm.split(" ").map { lev(it, nq) }).min() }
            .sortedBy { it.second }
        if (scored.isNotEmpty() && scored.first().second <= maxOf(1, nq.length / 4)) {
            pick(rows.filter { it.name == scored.first().first.name })?.let { return it }
        }
        val close = scored.map { it.first.name }.distinct().take(3)
        throw IllegalStateException("No contact matching '$q'." + if (close.isNotEmpty()) " Closest: ${close.joinToString(", ")}." else "")
    }

    private fun callContact(name: String): String {
        val (display, number) = contact(name)
        val i = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + android.net.Uri.encode(number)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return "Dialing $display."
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager = ctx.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()

    /** Opens the phone's own messages app with the text ready, used when direct sending is not possible. */
    private fun composer(display: String, number: String, text: String, why: String): String {
        val i = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:" + android.net.Uri.encode(number)))
            .putExtra("sms_body", text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return "$why, so I opened your messages app for $display with the text ready. Press send."
    }

    fun sendSms(arg: String): String {
        val p = arg.split("|", limit = 2)
        if (p.size < 2 || p[1].isBlank()) return "I need a recipient and a message."
        val text = p[1].trim()
        val (display, number) = contact(p[0])

        if (!has(Manifest.permission.SEND_SMS)) {
            return composer(display, number, text,
                "SMS permission is off (if Android refuses it: App info, menu, Allow restricted settings)")
        }
        val action = "com.jarvis.assistant.SMS_SENT_" + System.nanoTime()
        var receiver: BroadcastReceiver? = null
        return try {
            val sm = smsManager()
            val parts = sm.divideMessage(text)
            val latch = CountDownLatch(parts.size)
            val failures = AtomicInteger(0)
            val lastCode = AtomicInteger(0)
            receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (resultCode != Activity.RESULT_OK) { failures.incrementAndGet(); lastCode.set(resultCode) }
                    latch.countDown()
                }
            }
            ContextCompat.registerReceiver(ctx, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            val sent = ArrayList<PendingIntent>()
            for (k in parts.indices) {
                sent.add(PendingIntent.getBroadcast(ctx, k, Intent(action).setPackage(ctx.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            sm.sendMultipartTextMessage(number, null, parts, sent, null)
            val done = latch.await(20, TimeUnit.SECONDS)
            when {
                !done -> "SMS to $display was handed to the network but there is no confirmation yet. Check your messages app."
                failures.get() > 0 -> composer(display, number, text, "The network refused the SMS (code ${lastCode.get()})")
                else -> "SMS sent to $display."
            }
        } catch (e: SecurityException) {
            composer(display, number, text, "Android blocked direct SMS for this app")
        } catch (e: Exception) {
            composer(display, number, text, "Sending failed (${e.message})")
        } finally {
            try { receiver?.let { ctx.unregisterReceiver(it) } } catch (e: Exception) { /* not registered */ }
        }
    }

    private fun internationalNumber(raw: String): String {
        var n = raw.filter { it.isDigit() || it == '+' }
        if (n.startsWith("+")) return n.drop(1)
        if (n.startsWith("00")) return n.drop(2)
        if (n.startsWith("0")) {
            val iso = try {
                (ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).simCountryIso.uppercase(Locale.US)
            } catch (e: Exception) { "" }
            val code = mapOf(
                "MA" to "212", "DZ" to "213", "TN" to "216", "EG" to "20", "SA" to "966", "AE" to "971", "FR" to "33",
                "ES" to "34", "IT" to "39", "DE" to "49", "GB" to "44", "TR" to "90", "BE" to "32", "NL" to "31",
                "US" to "1", "CA" to "1", "QA" to "974", "KW" to "965", "JO" to "962", "LB" to "961"
            )[iso]
            if (code != null) n = code + n.drop(1)
        }
        return n
    }

    /** Sends a WhatsApp message: opens the chat with the text filled in, then taps Send if the screen agent is enabled. */
    fun whatsapp(contactName: String, text: String): String {
        if (text.isBlank()) return "I need the message text."
        val (display, raw) = contact(contactName)
        val number = internationalNumber(raw)
        val uri = android.net.Uri.parse("https://wa.me/$number?text=" + android.net.Uri.encode(text))
        var opened = false
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                opened = true
                break
            } catch (e: Exception) { /* try the next one */ }
        }
        if (!opened) return "WhatsApp is not installed on this phone."

        val svc = JarvisAccessibilityService.instance
            ?: return "Opened WhatsApp for $display with the message ready. Press send (enable the screen agent in Settings to send automatically)."
        Thread.sleep(2800)
        val clicked = svc.clickSend(
            listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send"),
            listOf("send", "إرسال", "envoyer", "enviar"),
            9000
        )
        return if (clicked) "WhatsApp message sent to $display."
        else "Opened WhatsApp for $display with the message ready, but I could not find the Send button. Press send."
    }

    // ---- calendar ----

    private fun calendarAdd(arg: String): String {
        val p = arg.split("|")
        val title = p.getOrElse(0) { "" }.trim()
        val startStr = p.getOrNull(1)?.trim() ?: return "I need a title and a start time like 2026-10-09 15:00."
        val minutes = p.getOrNull(2)?.trim()?.toIntOrNull() ?: 60
        val start = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(startStr)?.time
            ?: return "I could not read the time '$startStr'."
        need(Manifest.permission.READ_CALENDAR, "Calendar")
        need(Manifest.permission.WRITE_CALENDAR, "Calendar")

        var calId: Long? = null
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?", arrayOf("500"), null
        )?.use { if (it.moveToFirst()) calId = it.getLong(0) }
        val id = calId ?: return "No writable calendar found on this phone."

        val cv = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, id)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, start + minutes * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, cv) ?: return "The calendar refused the event."
        return "Added '$title' on $startStr."
    }

    fun calendarToday(): String {
        if (!has(Manifest.permission.READ_CALENDAR)) return "unavailable (calendar permission off)"
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val start = c.timeInMillis
        val end = start + 24 * 3600_000L
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, start)
        ContentUris.appendId(b, end)
        val fmt = SimpleDateFormat("HH:mm", Locale.US)
        val out = ArrayList<String>()
        try {
            ctx.contentResolver.query(
                b.build(), arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN),
                null, null, "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { while (it.moveToNext()) out.add(fmt.format(Date(it.getLong(1))) + " " + (it.getString(0) ?: "")) }
        } catch (e: Exception) {
            return "unavailable"
        }
        return if (out.isEmpty()) "no events" else out.joinToString("; ")
    }

    // ---- media, volume, torch, battery, location ----

    private fun audio(): AudioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun media(arg: String): String {
        val code = when (arg.trim().lowercase()) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause", "stop" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = audio()
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "Media: ${arg.ifBlank { "toggle" }}."
    }

    private fun volume(arg: String): String {
        val am = audio()
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val a = arg.trim().lowercase()
        val pct = a.toIntOrNull()
        when {
            pct != null -> am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * pct.coerceIn(0, 100) / 100), AudioManager.FLAG_SHOW_UI)
            a == "up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            a == "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            a == "mute" -> am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
            else -> return "Volume: say up, down, mute or a number from 0 to 100."
        }
        return "Volume adjusted."
    }

    private fun flashlight(arg: String): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "This phone has no flashlight."
        val on = arg.trim().lowercase() != "off"
        cm.setTorchMode(id, on)
        return if (on) "Flashlight on." else "Flashlight off."
    }

    fun batteryLine(): String {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "unknown"
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        if (level < 0) return "unknown"
        return "${level * 100 / scale}%" + if (charging) ", charging" else ""
    }

    @Suppress("DEPRECATION")
    private fun placeName(lat: Double, lon: Double): String? = try {
        Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
            ?.let { listOfNotNull(it.locality, it.countryName).joinToString(", ") }
    } catch (e: Exception) {
        null
    }

    private fun whereAmI(): String {
        need(Manifest.permission.ACCESS_COARSE_LOCATION, "Location")
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = lm.getProviders(true)
            .mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: SecurityException) { null } }
            .maxByOrNull { it.time } ?: return "No location fix yet. Open a maps app once, then ask again."
        val place = placeName(loc.latitude, loc.longitude)
        return "You are near " + (place ?: "%.4f, %.4f".format(loc.latitude, loc.longitude)) + "."
    }
}
