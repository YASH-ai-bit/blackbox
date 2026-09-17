package dev.blackbox.router

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import android.app.KeyguardManager
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** No root required. Does not toggle Wi-Fi, hotspot, firewall, or forwarding. */
@RunWith(AndroidJUnit4::class)
class BlackboxSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun navigationAndDeveloperModeWork() {
        check(!compose.activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            "Unlock the phone before running UI tests. The test cannot bypass a secure lock screen."
        }
        compose.activity.runOnUiThread { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        compose.waitUntil(timeoutMillis = 15_000) { compose.onAllNodesWithText("BLACKBOX").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("BLACKBOX").assertIsDisplayed()
        compose.onNodeWithText("Clients", useUnmergedTree = true).performClick()
        compose.onNodeWithText("CLIENTS").assertIsDisplayed()
        compose.onNodeWithText("Services", useUnmergedTree = true).performClick()
        compose.onNodeWithText("SERVICES").assertIsDisplayed()
        compose.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Developer Mode").assertIsDisplayed()
        if (compose.onAllNodes(isToggleable() and isOff()).fetchSemanticsNodes().isNotEmpty()) compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("Logs", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Hardware command diagnostics").performClick()
        compose.onNodeWithText("Developer Mode · sanitized command evidence. Addresses and sensitive fields are redacted.").assertIsDisplayed()
        compose.onNodeWithText("Network", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Hardware compatibility").performClick()
        compose.onNodeWithText("Built on evidence.").assertIsDisplayed()
    }
}
