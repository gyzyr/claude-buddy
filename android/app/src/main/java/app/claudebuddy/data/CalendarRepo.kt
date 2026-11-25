package app.claudebuddy.data

import app.claudebuddy.ui.tr
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class CalEvent(val title: String, val begin: LocalDateTime, val end: LocalDateTime, val allDay: Boolean, val calendar: String, val isOutlook: Boolean) {
    val timeLabel: String get() = if (allDay) tr("весь день", "all day") else begin.format(DateTimeFormatter.ofPattern("HH:mm"))
}

/**
 * События из системного календаря телефона. Туда синхронизируются Outlook (приложение Outlook)
 * и Google Календарь, так что отдельные входы не нужны.
 */
object CalendarRepo {
    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** События с текущего момента до конца завтрашнего дня (уже идущие — тоже). */
    suspend fun upcoming(ctx: Context): List<CalEvent> = withContext(Dispatchers.IO) {
        if (!hasPermission(ctx)) return@withContext emptyList()
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val start = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.now().plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(start.toString()).appendPath(end.toString()).build()
        val proj = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.VISIBLE, CalendarContract.Calendars.ACCOUNT_TYPE,
        )
        val out = mutableListOf<CalEvent>()
        runCatching {
            ctx.contentResolver.query(uri, proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(5) == 0) continue
                    val allDay = c.getInt(3) == 1
                    val b = c.getLong(1); val e = c.getLong(2)
                    if (e < now && !allDay) continue // уже закончилось
                    // у событий «весь день» время в UTC
                    val z = if (allDay) ZoneId.of("UTC") else zone
                    out += CalEvent(
                        title = c.getString(0)?.takeIf { it.isNotBlank() } ?: tr("(без названия)", "(untitled)"),
                        begin = LocalDateTime.ofInstant(Instant.ofEpochMilli(b), z),
                        end = LocalDateTime.ofInstant(Instant.ofEpochMilli(e), z),
                        allDay = allDay,
                        calendar = c.getString(4) ?: "",
                        isOutlook = c.getString(6)?.contains("outlook", ignoreCase = true) == true,
                    )
                }
            }
        }
        // дубли (одно событие в нескольких календарях) убираем
        out.distinctBy { Triple(it.title, it.begin, it.allDay) }
    }
}
