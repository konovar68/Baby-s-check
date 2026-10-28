package ru.babycheck

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class InsightsTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val day = LocalDate.of(2026, 10, 28)
    private fun ms(time: String) = LocalDateTime.parse(time).atZone(zone).toInstant().toEpochMilli()
    private fun sleep(from: String, to: String) = Event(kind="Сон", start=ms(from),end=ms(to))
    @Test fun splitsMidnightAndIncludesOnlyIntersection() {
        val events = listOf(sleep("2026-10-27T22:00", "2026-10-28T06:00"),sleep("2026-10-28T23:00", "2026-10-29T02:00"))
        val result = sleepDay(events,day,zone)
        assertEquals(7*3600000L,result.totalMs);assertEquals(2,result.sessions);assertEquals(210*60000L,result.averageMs)
        assertEquals(2*3600000L,sleepDay(events,day.minusDays(1),zone).totalMs)
    }
    @Test fun unionsOverlappingAndNestedRecordsWithoutDoubleCounting() {
        val events = listOf(sleep("2026-10-28T10:00","2026-10-28T12:00"),sleep("2026-10-28T11:00","2026-10-28T13:00"),sleep("2026-10-28T11:15","2026-10-28T11:30"))
        assertEquals(3*3600000L,sleepDay(events,day,zone).totalMs)
        assertEquals(1,sleepDay(events,day,zone).sessions)
    }
    @Test fun ignoresOpenWrongKindAndZeroLengthEvents() {
        val events = listOf(Event(kind="Сон",start=ms("2026-10-28T10:00")),Event(kind="Купание",start=0,end=Long.MAX_VALUE),sleep("2026-10-28T12:00","2026-10-28T12:00"))
        assertEquals(0L,sleepDay(events,day,zone).totalMs)
        assertEquals(0,sleepDay(events,day,zone).sessions)
    }
    @Test fun zeroFillsSevenAndThirtyDaysInOrder() {
        val event = sleep("2026-10-28T10:00","2026-10-28T11:00")
        for(n in listOf(7,30)) {
            val range = sleepRange(listOf(event),day,n,zone)
            assertEquals(n,range.size);assertEquals(day.minusDays(n-1L),range.first().day)
            assertEquals(day,range.last().day);assertEquals(3600000L,range.sumOf { it.totalMs })
        }
    }
    @Test fun respectsTwentyFiveHourDstDay() {
        val berlin=ZoneId.of("Europe/Berlin");val d=LocalDate.of(2026,10,25)
        val e=Event(kind="Сон",start=d.atStartOfDay(berlin).toInstant().toEpochMilli(),end=d.plusDays(1).atStartOfDay(berlin).toInstant().toEpochMilli())
        assertEquals(25*3600000L,sleepDay(listOf(e),d,berlin).totalMs)
    }
    @Test fun foodGroupsCaseAndWhitespaceAndKeepsEveryReaction() {
        val first=Event(kind="Прикорм",start=1,detail="  Кабачок ",note="Без реакции")
        val second=first.copy(id="two",start=2,detail="кабачок",note="Сыпь")
        val history=foodHistory(listOf(second,first,first.copy(kind="Смесь")))
        assertEquals(1,history.size);assertEquals(1L,history.single().first)
        assertEquals(listOf("Сыпь","Без реакции"),history.single().events.map { it.note })
    }
    @Test fun csvQuotesSeparatorsQuotesNewlinesAndPreventsFormulas() {
        val event=Event(id="id",kind="Прикорм",start=0,detail="Яблоко; груша",value=15.0,note="=HYPERLINK(\"bad\")\nстрока")
        val csv=TransferCsv.encode(listOf(event),Profile("Маша"),zone)
        assertTrue(csv.startsWith("\uFEFF"));assertTrue(csv.contains("\"Яблоко; груша\""))
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"bad\"\")\nстрока\""))
        assertTrue(csv.contains("1970-01-01T03:00:00+03:00"));assertTrue(csv.contains("\"15.0\";\"г\""))
    }
    @Test fun csvEmptyStillHasAHeader() { assertEquals(1,TransferCsv.encode(emptyList(),Profile()).trim().lines().size) }
}
