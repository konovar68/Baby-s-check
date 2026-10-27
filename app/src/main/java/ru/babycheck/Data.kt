package ru.babycheck

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

val kinds = listOf("Сон", "Грудное кормление", "Смесь", "Подгузник", "Прикорм", "Купание", "Рост", "Вес")
@Entity(tableName = "events")
data class Event(@PrimaryKey val id: String = UUID.randomUUID().toString(), val kind: String, val start: Long, val end: Long? = null, val detail: String = "", val value: Double? = null, val note: String = "", val updated: Long = System.currentTimeMillis())
@Dao
interface EventDao {
    @Query("SELECT * FROM events ORDER BY start DESC") fun observe(): Flow<List<Event>>
    @Query("SELECT * FROM events ORDER BY start DESC") suspend fun all(): List<Event>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(event: Event)
    @Query("DELETE FROM events WHERE id = :id") suspend fun delete(id: String)
}
@Database(entities = [Event::class], version = 1, exportSchema = false)
abstract class DiaryDb : RoomDatabase() { abstract fun events(): EventDao }

data class Profile(val name: String = "Малыш", val birthday: String = "")
data class Transfer(val events: List<Event>, val profile: Profile)
data class ImportPlan(val added: List<Event>, val changed: List<Event>, val skipped: Int)
fun planImport(existing: List<Event>, incoming: List<Event>, update: Boolean): ImportPlan {
    val byId = existing.associateBy { it.id }
    val added = incoming.filter { it.id !in byId }
    val changed = incoming.filter { it.id in byId && it != byId[it.id] && update }
    return ImportPlan(added, changed, incoming.size - added.size - changed.size)
}
object TransferJson {
    fun encode(events: List<Event>, profile: Profile): String = JSONObject().apply {
        put("format", "baby-check"); put("version", 1)
        put("profile", JSONObject().put("name", profile.name).put("birthday", profile.birthday))
        put("events", JSONArray().apply { events.forEach { e -> put(JSONObject().apply {
            put("id", e.id); put("kind", e.kind); put("start", e.start); put("end", e.end ?: JSONObject.NULL)
            put("detail", e.detail); put("value", e.value ?: JSONObject.NULL); put("note", e.note); put("updated", e.updated)
        }) } })
    }.toString(2)
    fun decode(text: String): Transfer {
        val root = JSONObject(text)
        require(root.getString("format") == "baby-check" && root.getInt("version") == 1) { "Неподдерживаемый формат файла" }
        val p = root.getJSONObject("profile")
        val profile = Profile(p.getString("name"), p.getString("birthday"))
        require(profile.name.length <= 100)
        if (profile.birthday.isNotBlank()) require(!LocalDate.parse(profile.birthday).isAfter(LocalDate.now())) { "Дата рождения в будущем" }
        val a = root.getJSONArray("events")
        require(a.length() <= 100000) { "Слишком много записей" }
        val events = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Event(o.getString("id"), o.getString("kind"), o.getLong("start"), if(o.isNull("end")) null else o.getLong("end"), o.getString("detail"), if(o.isNull("value")) null else o.getDouble("value"), o.getString("note"), o.getLong("updated")).also { e ->
                require(e.id.isNotBlank() && e.id.length <= 100 && e.kind in kinds && e.start >= 0 && e.updated >= 0)
                require(e.end == null || e.end >= e.start) { "Окончание раньше начала" }
                require(e.value == null || (e.value.isFinite() && e.value > 0))
                require(e.detail.length <= 1000 && e.note.length <= 10000)
                require(e.kind !in listOf("Рост", "Вес", "Смесь") || e.value != null) { "Не указано измерение" }
                require(e.kind !in listOf("Сон", "Грудное кормление") || e.end != null) { "Не указано окончание" }
                require(e.kind != "Прикорм" || e.detail.isNotBlank()) { "Не указан продукт" }
                require(e.kind != "Грудное кормление" || e.detail in listOf("Левая", "Правая"))
                require(e.kind != "Подгузник" || e.detail in listOf("Мочеиспускание", "Стул", "Смешанный"))
            }
        }
        require(events.distinctBy { it.id }.size == events.size) { "В файле повторяются идентификаторы" }
        return Transfer(events, profile)
    }
}
