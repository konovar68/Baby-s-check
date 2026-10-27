package ru.babycheck

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiarySmokeTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun savesEventAndRetainsItAfterRecreation() {
        ui.onNodeWithText("+ Добавить событие").performClick()
        ui.onNodeWithText("Сон ▾").performClick()
        ui.onNodeWithText("Купание").performClick()
        ui.onNodeWithText("Заметки").performTextInput("Проверка сохранения")
        ui.onNodeWithText("Сохранить").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("Проверка сохранения").fetchSemanticsNodes().isNotEmpty() }
        ui.activityRule.scenario.recreate()
        ui.waitUntil(10000) { ui.onAllNodesWithText("Проверка сохранения").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Купание").assertIsDisplayed()
    }
    @Test fun timerSurvivesRecreationAndCanBeSaved() {
        ui.onNodeWithText("Начать").performClick()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("Завершить и сохранить").assertIsDisplayed().performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("Завершить и сохранить").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithText("Начать").assertIsDisplayed()
    }
}
