package com.ekranceviri

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @Test
    fun mainScreenOpensAndShowsPrimaryControls() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(
                androidx.lifecycle.Lifecycle.State.RESUMED,
                scenario.state
            )
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                assertTrue("Activity window must be attached and visible", decor.isShown)

                val visibleTexts = mutableListOf<String>()
                fun collect(view: View) {
                    if (view is TextView) visibleTexts += view.text?.toString().orEmpty()
                    if (view is ViewGroup) {
                        for (i in 0 until view.childCount) collect(view.getChildAt(i))
                    }
                }
                collect(decor)

                assertTrue("App title is missing", visibleTexts.contains("Ekran Çeviri"))
                assertTrue("Start control is missing", visibleTexts.contains("BAŞLAT"))
                assertTrue("Translation engine section is missing", visibleTexts.contains("Çeviri motoru"))
            }
        }
    }
}
