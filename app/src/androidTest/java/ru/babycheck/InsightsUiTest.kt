package ru.babycheck

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class InsightsUiTest {
    @get:Rule val ui = createComposeRule()
    @Test fun sleepChartDisplaysSavedHoursAndChangesRange() {
        val start = LocalDate.now().atTime(10,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        ui.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { SleepChart(listOf(Event(kind="Сон",start=start,end=start+7200000))) } } }
        ui.onNodeWithTag("sleep-chart").assertExists()
        ui.onNodeWithText("Сон: 2 ч 0 мин · Сессий: 1").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("30 дней").performScrollTo().performClick()
        ui.onNodeWithText("Среднее включает все 30 дней. Нулевой столбец означает отсутствие записанного сна.").assertExists()
    }
    @Test fun foodHistorySearchAndExpandShowsSavedReaction() {
        val events = listOf(Event(kind="Прикорм",start=1,detail="Кабачок",note="Без реакции"),Event(kind="Прикорм",start=2,detail="Яблоко",note="Наблюдение"))
        ui.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { FoodHistoryView(events) } } }
        ui.onNodeWithText("Найти продукт").performTextInput("Кабачок")
        ui.onNodeWithText("Яблоко").assertDoesNotExist()
        ui.onNodeWithText("Показать кормления и реакции").performScrollTo().performClick()
        ui.onNodeWithText("Без реакции").performScrollTo().assertIsDisplayed()
    }
}
