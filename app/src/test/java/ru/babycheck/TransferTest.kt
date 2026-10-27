package ru.babycheck

import org.junit.Assert.*
import org.junit.Test

class TransferTest {
    private val original = Event("one", "Прикорм", 1700000000000, detail="Кабачок", value=15.0, note="Без реакции", updated=1700000000000)
    private val profile = Profile("Миша", "2024-01-01")
    @Test fun roundTripPreservesAllFields() {
        val timed = Event("two", "Сон", 1700000000000, 1700003600000, updated=1700003600000)
        val decoded = TransferJson.decode(TransferJson.encode(listOf(original,timed),profile))
        assertEquals(listOf(original,timed),decoded.events)
        assertEquals(profile,decoded.profile)
    }
    @Test fun repeatedImportDoesNotDuplicate() {
        val plan = planImport(listOf(original),listOf(original),true)
        assertEquals(0,plan.added.size); assertEquals(0,plan.changed.size); assertEquals(1,plan.skipped)
    }
    @Test fun addOnlyPreservesExistingAndKeepsMissingRows() {
        val newer = original.copy(note="Изменено")
        val untouched = original.copy(id="untouched")
        val added = original.copy(id="new")
        val plan = planImport(listOf(original,untouched),listOf(newer,added),false)
        assertEquals(listOf(added),plan.added); assertTrue(plan.changed.isEmpty()); assertEquals(1,plan.skipped)
    }
    @Test fun updateUsesStableIdentity() {
        val changed = original.copy(value=30.0)
        val plan = planImport(listOf(original),listOf(changed),true)
        assertEquals(listOf(changed),plan.changed); assertTrue(plan.added.isEmpty())
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsDuplicateIds() {
        TransferJson.decode(TransferJson.encode(listOf(original,original),profile))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsNegativeMeasurement() {
        TransferJson.decode(TransferJson.encode(listOf(original.copy(value=-1.0)),profile))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsInvalidInterval() {
        TransferJson.decode(TransferJson.encode(listOf(original.copy(end=1)),profile))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsUnsupportedVersion() {
        TransferJson.decode(TransferJson.encode(listOf(original),profile).replace("\"version\": 1","\"version\": 99"))
    }
}
