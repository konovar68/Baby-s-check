package ru.babycheck

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SleepInterval(val start: Long, val end: Long)
data class SleepDay(val day: LocalDate, val intervals: List<SleepInterval>) {
    val totalMs: Long get() = intervals.sumOf { it.end - it.start }
    val sessions: Int get() = intervals.size
    val averageMs: Long get() = if (sessions == 0) 0 else totalMs / sessions
}

// Clip at real local midnight (including DST), then union overlaps to avoid double counting.
fun sleepDay(events: List<Event>, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): SleepDay {
    val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val until = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val pieces = events.asSequence().filter { it.kind == "Сон" && it.end != null }
        .map { SleepInterval(maxOf(it.start, from), minOf(it.end!!, until)) }
        .filter { it.end > it.start }.sortedBy { it.start }.toList()
    val merged = mutableListOf<SleepInterval>()
    pieces.forEach { next ->
        val last = merged.lastOrNull()
        if (last != null && next.start < last.end) merged[merged.lastIndex] = SleepInterval(last.start, maxOf(last.end, next.end))
        else merged.add(next)
    }
    return SleepDay(day, merged)
}
fun sleepRange(events: List<Event>, last: LocalDate, days: Int, zone: ZoneId = ZoneId.systemDefault()): List<SleepDay> {
    require(days in 1..366)
    return (days - 1 downTo 0).map { sleepDay(events, last.minusDays(it.toLong()), zone) }
}

data class FoodHistory(val product: String, val events: List<Event>) {
    val first: Long get() = events.minOf { it.start }
    val latest: Long get() = events.maxOf { it.start }
}
fun foodHistory(events: List<Event>): List<FoodHistory> = events.filter { it.kind == "Прикорм" && it.detail.isNotBlank() }
    .groupBy { it.detail.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT) }
    .map { (_, entries) -> FoodHistory(entries.minBy { it.start }.detail.trim(), entries.sortedByDescending { it.start }) }
    .sortedByDescending { it.latest }

object TransferCsv {
    private fun cell(value: String): String {
        // Quoting alone does not prevent spreadsheet formula execution, including after leading whitespace.
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'" + value else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
    fun encode(events: List<Event>, profile: Profile, zone: ZoneId = ZoneId.systemDefault()): String {
        val time = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        fun timestamp(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(time)
        val rows = listOf(listOf("ID", "Ребёнок", "Дата рождения", "Событие", "Начало", "Окончание", "Длительность, мин", "Подробности", "Значение", "Единица", "Заметки")) +
            events.sortedBy { it.start }.map { e -> listOf(e.id, profile.name, profile.birthday, e.kind,
                timestamp(e.start), e.end?.let(::timestamp) ?: "", e.end?.let { ((it-e.start)/60000.0).toString() } ?: "",
                e.detail, e.value?.toString() ?: "", when(e.kind) { "Рост" -> "см"; "Вес" -> "кг"; "Смесь" -> "мл"; "Прикорм" -> "г"; else -> "" }, e.note) }
        return "\uFEFF" + rows.joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(";", transform = ::cell) }
    }
}
