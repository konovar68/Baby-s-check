package ru.babycheck

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.*
import java.time.format.DateTimeFormatter

private fun length(ms: Long): String = "${ms / 3600000} ч ${ms / 60000 % 60} мин"
private fun local(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
private val fullDate = DateTimeFormatter.ofPattern("dd.MM.yyyy")

@Composable
fun SleepChart(events: List<Event>) {
    val context = LocalContext.current
    var days by remember { mutableIntStateOf(7) }
    var last by remember { mutableStateOf(LocalDate.now()) }
    var selected by remember { mutableStateOf(last) }
    val history = remember(events, last, days) { sleepRange(events, last, days) }
    val chosen = history.firstOrNull { it.day == selected } ?: history.last()
    val total = history.sumOf { it.totalMs }
    Text("Сон по дням", style = MaterialTheme.typography.titleLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(7,30).forEach { n -> FilterChip(days == n, { days = n; selected = last }, label = { Text("$n дней") }) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { last = last.minusDays(days.toLong()); selected = last }) { Text("‹ Ранее") }
        TextButton(onClick = { DatePickerDialog(context, { _,y,m,d -> last = LocalDate.of(y,m+1,d); selected = last },last.year,last.monthValue-1,last.dayOfMonth).show() }) { Text("До ${last.format(fullDate)}") }
        TextButton(onClick = { last = last.plusDays(days.toLong()); selected = last }) { Text("Позже ›") }
    }
    Text("Всего: ${length(total)} · В среднем за день: ${length(total / days)}", style = MaterialTheme.typography.bodyMedium)
    Text("Среднее включает все $days дней. Нулевой столбец означает отсутствие записанного сна.", style = MaterialTheme.typography.bodySmall)
    val maxHours = kotlin.math.ceil((history.maxOfOrNull { it.totalMs } ?: 0) / 3600000.0).toInt().coerceAtLeast(1)
    Text("Шкала: 0–$maxHours ч. Нажмите на день для подробностей.", style = MaterialTheme.typography.bodySmall)
    val scroll = rememberScrollState()
    LaunchedEffect(last, days) { withFrameNanos { }; scroll.scrollTo(scroll.maxValue) }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("sleep-chart")) {
        val width = maxWidth / 7
        Row(Modifier.horizontalScroll(scroll).height(205.dp)) {
            history.forEach { point ->
                Column(Modifier.width(width).fillMaxHeight().clickable { selected = point.day }
                    .semantics { contentDescription = "Сон ${point.day.format(fullDate)}: ${length(point.totalMs)}" }.padding(horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${point.totalMs / 3600000}.${point.totalMs / 360000 % 10}", style = MaterialTheme.typography.labelSmall)
                        Box(Modifier.fillMaxWidth().height((145 * point.totalMs / (maxHours * 3600000.0)).coerceAtLeast(2.0).dp)
                            .background(if(point.day == chosen.day) MaterialTheme.colorScheme.primary else Color(0xFFA7C5BA)))
                    }
                    Text(point.day.format(DateTimeFormatter.ofPattern("dd.MM")), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(chosen.day.format(fullDate), style = MaterialTheme.typography.titleMedium)
            Text("Сон: ${length(chosen.totalMs)} · Сессий: ${chosen.sessions}")
            Text("Средняя сессия в этом дне: ${length(chosen.averageMs)}")
            if(chosen.intervals.isEmpty()) Text("Нет завершённых записей сна")
            chosen.intervals.forEach { interval ->
                Text("${local(interval.start).format(DateTimeFormatter.ofPattern("HH:mm"))} — ${local(interval.end).format(DateTimeFormatter.ofPattern("HH:mm"))}${if(local(interval.end).toLocalDate() != chosen.day) " (+1 день)" else ""} · ${length(interval.end-interval.start)}")
            }
        }
    }
    Text("Учитывается завершённый сон. Переход через полночь разделён между днями; пересекающиеся записи объединены. Активный таймер появится после сохранения.", style = MaterialTheme.typography.bodySmall)
    HorizontalDivider(Modifier.padding(vertical = 16.dp))
}

@Composable
fun FoodHistoryView(events: List<Event>) {
    val products = remember(events) { foodHistory(events) }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    Text("История прикорма", style = MaterialTheme.typography.titleLarge)
    Text("Продуктов: ${products.size}. Продукты с одинаковым названием объединяются; регистр и лишние пробелы не учитываются.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(query, { query = it }, label = { Text("Найти продукт") }, modifier = Modifier.fillMaxWidth())
    val found = products.filter { it.product.contains(query.trim(), ignoreCase = true) }
    if(found.isEmpty()) Text(if(products.isEmpty()) "Добавьте прикорм в дневнике, чтобы увидеть историю продуктов." else "Продукт не найден")
    found.forEach { product ->
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(product.product, style = MaterialTheme.typography.titleMedium)
                Text("Первый раз: ${local(product.first).format(fullDate)}")
                Text("Последний раз: ${local(product.latest).format(fullDate)} · Записей: ${product.events.size}")
                TextButton(onClick = { expanded = if(expanded == product.product) null else product.product }) { Text(if(expanded == product.product) "Свернуть" else "Показать кормления и реакции") }
                if(expanded == product.product) product.events.forEach { event ->
                    Text(local(event.start).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + (event.value?.let { " · $it г" } ?: ""))
                    Text(event.note.ifBlank { "Реакция и заметки не указаны" }, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
