package com.ethran.notable.editor

import androidx.compose.material.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimpleComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun simpleTest() {
        android.util.Log.i("SimpleComposeTest", "Starting simpleTest")
        composeRule.setContent {
            Text("Hello World")
        }
        android.util.Log.i("SimpleComposeTest", "Content set")
        composeRule.waitForIdle()
        android.util.Log.i("SimpleComposeTest", "Done")
    }
}
