package com.jarvis.assistant

import android.Manifest
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
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Direct control of the phone: contacts, SMS, calendar, media, volume, flashlight, battery, location. */
class Device(private val ctx: Context) {

    companion object {
        val TYPES = setOf(
            "call_contact", "send_sms", "calendar_add", "media", "volume", "flashlight", "battery", "where_am_i"
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

    // ---- contacts, calls, SMS ----

    private fun contact(query: String): Pair<String, String> {
        val q = query.trim()
        if (q.count { it.isDigit() } >= 5) return q to q.filter { it.isDigit() || it == '+' }
        need(Manifest.permission.READ_CONTACTS, "Contacts")
        val cursor = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"), null
        )
        var best: Pair<String, String>? = null
        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: continue
                val number = it.getString(1) ?: continue
                if (best == null || name.equals(q, ignoreCase = true)) best = name to number
                if (name.equals(q, ignoreCase = true)) break
            }
        }
        return best ?: throw IllegalStateException("No contact named '$q'.")
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

    private fun sendSms(arg: String): String {
        val p = arg.split("|", limit = 2)
        if (p.size < 2 || p[1].isBlank()) return "I need a recipient and a message."
        need(Manifest.permission.SEND_SMS, "SMS")
        val (display, number) = contact(p[0])
        val sm = smsManager()
        sm.sendMultipartTextMessage(number, null, sm.divideMessage(p[1]), null, null)
        return "SMS sent to $display."
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
