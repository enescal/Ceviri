package com.ekranceviri

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @Test
    fun mainScreenOpensAndShowsPrimaryControls() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Ekran Çeviri")).check(matches(isDisplayed()))
            onView(withText("BAŞLAT")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Çeviri motoru")).check(matches(isDisplayed()))
        }
    }
}
