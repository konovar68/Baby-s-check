package ru.babycheck

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

private val stamp = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
private fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
private fun duration(ms: Long): String { val m = ms.coerceAtLeast(0) / 60000; return "${m / 60} ч ${m % 60} мин" }
class MainActivity : ComponentActivity() {
    private lateinit var db: DiaryDb
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Room.databaseBuilder(applicationContext, DiaryDb::class.java, "diary.db").build()
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF426B62), secondaryContainer = Color(0xFFE3EFE9), background = Color(0xFFFAFAF6))) { Surface(Modifier.fillMaxSize()) { Diary(db) } } }
    }
}
// Small synchronous preference writes keep timer identity durable before UI acknowledgement.
@android.annotation.SuppressLint("ApplySharedPref")
@Composable
private fun Diary(db: DiaryDb) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("baby", 0) }
    var profile by remember { mutableStateOf(Profile(prefs.getString("name", "Малыш")!!, prefs.getString("birthday", "")!!)) }
    val eventFlow = remember(db) { db.events().observe() }
    val all by eventFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var day by remember { mutableStateOf(LocalDate.now()) }
    var tab by remember { mutableIntStateOf(0) }
    var editor by remember { mutableStateOf<Event?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Event?>(null) }
    var showProfile by remember { mutableStateOf(false) }
    var transfer by remember { mutableStateOf<Transfer?>(null) }
    var update by remember { mutableStateOf(false) }
    var restoreProfile by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var timerStart by remember { mutableLongStateOf(prefs.getLong("timerStart", 0)) }
    var timerKind by remember { mutableStateOf(prefs.getString("timerKind", "Сон")!!) }
    var timerSide by remember { mutableStateOf(prefs.getString("timerSide", "Левая")!!) }
    var error by remember { mutableStateOf<String?>(null) }
    fun notify(message: String) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val payload = pendingExport
        pendingExport = null
        if (uri != null && payload != null) scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { (context.contentResolver.openOutputStream(uri, "wt") ?: error("Не удалось открыть файл")).bufferedWriter().use { it.write(payload) } }; notify("Выгрузка сохранена") }
            catch (e: Exception) { error = "Не удалось сохранить файл: ${e.localizedMessage}" }
            finally { busy = false }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                transfer = withContext(Dispatchers.IO) {
                    val bytes = (context.contentResolver.openInputStream(uri) ?: error("Не удалось открыть файл")).use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            require(output.size() + count <= 20 * 1024 * 1024) { "Файл больше 20 МБ" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray() }
                    require(bytes.size <= 20 * 1024 * 1024) { "Файл больше 20 МБ" }
                    TransferJson.decode(bytes.toString(Charsets.UTF_8))
                }; update = false; restoreProfile = false
            } catch (e: Exception) { error = "Импорт отменён. ${e.localizedMessage ?: "Неверный файл"}" }
            finally { busy = false }
        }
    }
    val entries = all.filter { date(it.start).toLocalDate() == day }
    fun export(onlyDay: Boolean) {
        pendingExport = TransferJson.encode(if (onlyDay) entries else all, profile)
        exporter.launch("baby-check-${if(onlyDay) day.toString() else "all"}.json")
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Дневник малыша", style = MaterialTheme.typography.headlineSmall)
                Text(profile.name + if (profile.birthday.isNotBlank()) " · ${java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(profile.birthday), LocalDate.now())} дн." else "", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { showProfile = true }, enabled = !busy) { Text("Профиль") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Дневник", "Графики", "Данные").forEachIndexed { i, label -> FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(label) }) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        when (tab) {
            0 -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { day = day.minusDays(1) }) { Text("‹ День") }
                    TextButton(onClick = { val d = day; DatePickerDialog(context, { _, y, m, n -> day = LocalDate.of(y,m+1,n) },d.year,d.monthValue-1,d.dayOfMonth).show() }) { Text(day.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))) }
                    TextButton(onClick = { day = day.plusDays(1) }) { Text("День ›") }
                }
                val dayStart = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val dayEnd = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val sleep = all.filter { it.kind == "Сон" }.sumOf { ((it.end ?: it.start).coerceAtMost(dayEnd) - it.start.coerceAtLeast(dayStart)).coerceAtLeast(0) }
                Text("Сон: ${duration(sleep)} · Кормлений: ${entries.count { it.kind in listOf("Грудное кормление", "Смесь", "Прикорм") }} · Подгузников: ${entries.count { it.kind == "Подгузник" }}", style = MaterialTheme.typography.bodySmall)
                Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        if (timerStart == 0L) {
                            Text("Быстрый таймер", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Сон", "Грудное кормление").forEach { k -> FilterChip(timerKind == k, { timerKind = k }, label = { Text(if(k == "Сон") "Сон" else "Грудь") }) }
                            }
                            if (timerKind == "Грудное кормление") Row { listOf("Левая", "Правая").forEach { side -> FilterChip(timerSide == side, { timerSide = side }, label = { Text(side) }) } }
                            Button(onClick = {
                                timerStart = System.currentTimeMillis()
                                prefs.edit().putLong("timerStart",timerStart).putString("timerKind",timerKind).putString("timerSide",timerSide).putString("timerId",UUID.randomUUID().toString()).commit()
                            }, enabled = !busy) { Text("Начать") }
                        } else {
                            Text("$timerKind · ${duration(now - timerStart)}")
                            Text("Начало: ${date(timerStart).format(stamp)}", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { scope.launch {
                                busy = true
                                try {
                                    db.events().put(Event(id = prefs.getString("timerId",null) ?: UUID.randomUUID().toString(), kind = timerKind,start = timerStart,end = System.currentTimeMillis().coerceAtLeast(timerStart),detail = if(timerKind == "Грудное кормление") timerSide else ""))
                                    prefs.edit().remove("timerStart").remove("timerId").commit(); timerStart = 0
                                } catch(e: Exception) { error = "Не удалось сохранить таймер: ${e.localizedMessage}" } finally { busy = false }
                            } }, enabled = !busy) { Text("Завершить и сохранить") }
                        }
                    }
                }
                Button(onClick = { editor = null; showEditor = true }, Modifier.fillMaxWidth(), enabled = !busy) { Text("+ Добавить событие") }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    if (entries.isEmpty()) item { Text("За этот день записей пока нет. Добавьте первое событие.", Modifier.padding(16.dp)) }
                    items(entries, key = { it.id }) { e ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                            Text(e.kind, style = MaterialTheme.typography.titleMedium)
                            Text(date(e.start).format(stamp) + (e.end?.let { " → ${date(it).format(stamp)} · ${duration(it-e.start)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                            if (e.detail.isNotBlank()) Text(e.detail)
                            e.value?.let { Text("$it ${when(e.kind) { "Рост" -> "см"; "Вес" -> "кг"; "Смесь" -> "мл"; else -> "г" }}") }
                            if (e.note.isNotBlank()) Text(e.note)
                            Row { TextButton(onClick = { editor = e; showEditor = true }, enabled = !busy) { Text("Изменить") }; TextButton(onClick = { deleting = e }, enabled = !busy) { Text("Удалить") } }
                        } }
                    }
                }
            }
            1 -> Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Рост и вес", style = MaterialTheme.typography.titleLarge)
                Text("Ваши измерения по датам; графики не являются медицинской оценкой.", style = MaterialTheme.typography.bodySmall)
                listOf("Рост", "Вес").forEach { kind ->
                    val points = all.filter { it.kind == kind && it.value != null }.sortedBy { it.start }
                    Text("$kind (${if(kind == "Рост") "см" else "кг"})", Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleMedium)
                    if(points.isEmpty()) Text("Добавьте измерение в дневнике") else {
                        Canvas(Modifier.fillMaxWidth().height(160.dp).padding(16.dp)) {
                            val min = points.minOf { it.value!! }; val max = points.maxOf { it.value!! }
                            val span = (points.last().start - points.first().start).coerceAtLeast(1)
                            val coords = points.map { Offset(if(points.size == 1) size.width/2 else ((it.start-points.first().start).toDouble()/span*size.width).toFloat(), (size.height - ((it.value!!-min)/(max-min).coerceAtLeast(1.0))*size.height).toFloat().coerceIn(4f,size.height-4)) }
                            coords.zipWithNext().forEach { (a,b) -> drawLine(Color(0xFF426B62),a,b,5f) }
                            coords.forEach { drawCircle(Color(0xFF426B62),6f,it) }
                        }
                        points.takeLast(10).forEach { Text("${date(it.start).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))} — ${it.value}") }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            2 -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Данные на этом телефоне", style = MaterialTheme.typography.titleLarge)
                Text("Записей: ${all.size}. Приложение работает без интернета. Сохраняйте JSON в надёжное место: удаление приложения удаляет локальную базу. Незавершённый таймер не экспортируется — завершите его перед переносом.")
                Button(onClick = { export(false) }, enabled = !busy) { Text("Выгрузить все данные") }
                Text("Выбранный день: $day. Событие относится к дню своего начала; полный экспорт включает всю историю.")
                OutlinedButton(onClick = { export(true) }, enabled = !busy) { Text("Выгрузить выбранный день") }
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, enabled = !busy) { Text("Загрузить JSON") }
                Text("Импорт ничего не удаляет. Перед применением вы увидите количество изменений. При обновлении создаётся внутренняя резервная копия.")
                OutlinedButton(onClick = { scope.launch {
                    try { val data = withContext(Dispatchers.IO) { File(context.filesDir,"before-import.json").readText() }; pendingExport = data; exporter.launch("baby-check-before-import.json") }
                    catch(e: Exception) { error = "Резервной копии пока нет. Она появится перед первым обновлением данных через импорт." }
                } }, enabled = !busy) { Text("Выгрузить копию до импорта") }
                Text("Файл содержит личные данные ребёнка. Передавайте его только тем, кому доверяете.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (showEditor) EventEditor(editor, day, onDismiss = { showEditor = false }) { e -> scope.launch {
        try { db.events().put(e); showEditor = false } catch(ex: Exception) { error = "Не удалось сохранить: ${ex.localizedMessage}" }
    } }
    deleting?.let { e -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Удалить запись?") }, text = { Text("${e.kind}, ${date(e.start).format(stamp)}. Действие нельзя отменить без резервной копии.") }, confirmButton = { TextButton(onClick = { scope.launch { try { db.events().delete(e.id); deleting = null } catch(ex: Exception) { error = "Не удалось удалить запись" } } }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } }) }
    if (showProfile) ProfileEditor(profile, { showProfile = false }) { p -> profile = p; prefs.edit().putString("name",p.name).putString("birthday",p.birthday).commit(); showProfile = false }
    transfer?.let { incoming ->
        val plan = planImport(all,incoming.events,update)
        AlertDialog(onDismissRequest = { if(!busy) transfer = null }, title = { Text("Предпросмотр импорта") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Добавить: ${plan.added.size}\nОбновить: ${plan.changed.size}\nПропустить: ${plan.skipped}\nУдалить: 0")
            Row { Checkbox(update, { update = it }, enabled = !busy); Text("Обновить существующие записи") }
            Text("При обновлении данные из файла заменят запись с тем же ID, даже если файл старее.", style = MaterialTheme.typography.bodySmall)
            Row { Checkbox(restoreProfile, { restoreProfile = it }, enabled = !busy); Text("Загрузить профиль «${incoming.profile.name}»") }
            Text("Импортируйте данные только одного ребёнка в этот дневник.")
        } }, confirmButton = { TextButton(enabled = !busy,onClick = { scope.launch {
            busy = true
            try {
                db.withTransaction {
                    val current = db.events().all()
                    val freshPlan = planImport(current,incoming.events,update)
                    if(freshPlan.changed.isNotEmpty() || restoreProfile) withContext(Dispatchers.IO) {
                        val temp = File(context.filesDir,"before-import.tmp")
                        temp.writeText(TransferJson.encode(current,profile))
                        check(temp.renameTo(File(context.filesDir,"before-import.json"))) { "Не удалось сохранить резервную копию" }
                    }
                    (freshPlan.added + freshPlan.changed).forEach { db.events().put(it) }
                }
                if(restoreProfile) { profile = incoming.profile; prefs.edit().putString("name",profile.name).putString("birthday",profile.birthday).commit() }
                transfer = null; notify("Импорт завершён")
            } catch(e: Exception) { error = "Импорт не выполнен: ${e.localizedMessage}" } finally { busy = false }
        } }) { Text("Применить") } }, dismissButton = { TextButton(enabled = !busy,onClick = { transfer = null }) { Text("Отмена") } })
    }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("Сообщение") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("Понятно") } }) }
}

@Composable
private fun MomentPicker(label: String, value: Long, change: (Long) -> Unit) {
    val context = LocalContext.current
    TextButton(onClick = {
        val z = date(value)
        DatePickerDialog(context, { _, y,m,d -> TimePickerDialog(context, { _,h,min -> change(LocalDateTime.of(y,m+1,d,h,min).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()) },z.hour,z.minute,true).show() },z.year,z.monthValue-1,z.dayOfMonth).show()
    }) { Text("$label: ${date(value).format(stamp)}") }
}
@Composable
private fun EventEditor(existing: Event?, day: LocalDate, onDismiss: () -> Unit, save: (Event) -> Unit) {
    val eventId = remember { existing?.id ?: UUID.randomUUID().toString() }
    var kind by remember { mutableStateOf(existing?.kind ?: "Сон") }
    var start by remember { mutableLongStateOf(existing?.start ?: day.atTime(LocalTime.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()) }
    var end by remember { mutableLongStateOf(existing?.end ?: start) }
    var detail by remember { mutableStateOf(existing?.detail ?: "") }
    var value by remember { mutableStateOf(existing?.value?.toString() ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var error by remember { mutableStateOf("") }
    var chooseKind by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if(existing == null) "Новое событие" else "Изменить событие") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick = { chooseKind = true }) { Text("$kind ▾") }
                DropdownMenu(expanded = chooseKind, onDismissRequest = { chooseKind = false }) {
                    kinds.forEach { k -> DropdownMenuItem(text = { Text(k) }, onClick = { kind = k; detail = ""; value = ""; chooseKind = false }) }
                }
            }
            MomentPicker("Начало",start) { start = it }
            if(kind in listOf("Сон", "Грудное кормление")) MomentPicker("Окончание",end) { end = it }
            when(kind) {
                "Грудное кормление" -> Row { listOf("Левая", "Правая").forEach { s -> FilterChip(detail == s,{detail = s},label = { Text(s) }) } }
                "Подгузник" -> Column { listOf("Мочеиспускание", "Стул", "Смешанный").forEach { s -> FilterChip(detail == s,{detail = s},label = { Text(s) }) } }
                "Прикорм" -> OutlinedTextField(detail,{detail=it.take(1000)},label = {Text("Продукт / блюдо")},modifier=Modifier.fillMaxWidth())
            }
            if(kind in listOf("Смесь", "Прикорм", "Рост", "Вес")) OutlinedTextField(value,{value=it},label={Text(when(kind){"Смесь"->"Объём, мл";"Прикорм"->"Количество, г (необязательно)";"Рост"->"Рост, см";else->"Вес, кг"})}, keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
            OutlinedTextField(note,{note=it.take(10000)},label={Text(if(kind=="Прикорм") "Реакция и заметки" else "Заметки")},modifier=Modifier.fillMaxWidth())
            if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        val numeric = value.replace(',','.').toDoubleOrNull()
        val timed = kind in listOf("Сон","Грудное кормление")
        error = when {
            timed && end < start -> "Окончание не может быть раньше начала"
            kind in listOf("Смесь","Рост","Вес") && (numeric == null || !numeric.isFinite() || numeric <= 0) -> "Укажите положительное число"
            kind=="Прикорм" && detail.isBlank() -> "Укажите продукт"
            kind=="Прикорм" && value.isNotBlank() && (numeric==null || !numeric.isFinite() || numeric<=0) -> "Количество должно быть положительным"
            kind in listOf("Подгузник","Грудное кормление") && detail.isBlank() -> "Выберите вариант"
            else -> ""
        }
        if(error.isBlank()) save(Event(eventId,kind,start,if(timed) end else null,detail.trim(),if(kind in listOf("Смесь","Рост","Вес","Прикорм")) numeric else null,note.trim()))
    }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick=onDismiss) { Text("Отмена") } })
}
@Composable
private fun ProfileEditor(profile: Profile, dismiss: () -> Unit, save: (Profile) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(profile.name) }
    var birthday by remember { mutableStateOf(profile.birthday) }
    AlertDialog(onDismissRequest=dismiss,title={Text("Профиль ребёнка")},text={Column {
        OutlinedTextField(name,{name=it.take(100)},label={Text("Имя")})
        TextButton(onClick={ val d=if(birthday.isBlank()) LocalDate.now() else LocalDate.parse(birthday); DatePickerDialog(context,{_,y,m,n->birthday=LocalDate.of(y,m+1,n).toString()},d.year,d.monthValue-1,d.dayOfMonth).apply { datePicker.maxDate=System.currentTimeMillis() }.show() }) { Text(if(birthday.isBlank()) "Указать дату рождения" else "Дата рождения: $birthday") }
    }},confirmButton={TextButton(enabled=name.isNotBlank(),onClick={save(Profile(name.trim(),birthday))}){Text("Сохранить")}},dismissButton={TextButton(onClick=dismiss){Text("Отмена")}})
}
