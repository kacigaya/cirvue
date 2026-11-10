package app.cirvue

import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.cirvue.models.ModelPack
import app.cirvue.ui.HomeScreen
import app.cirvue.ui.theme.CirvueTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as CirvueApp
    private var isAssistant by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val memory = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        setContent {
            CirvueTheme {
                HomeScreen(
                    isAssistant = isAssistant,
                    fullVisionSupported = memory.totalMem >= ModelPack.FULL_MIN_MEMORY_BYTES,
                    settings = app.settings,
                    models = app.models,
                    onAssistantSettings = ::openAssistantSettings,
                    onCamera = {
                        startActivity(Intent(this, SearchActivity::class.java).setAction(SearchActivity.ACTION_CAMERA))
                    },
                    onDownload = { lifecycleScope.launch { app.models.download(it) } },
                    onRemove = { lifecycleScope.launch { app.models.remove(it) } },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have changed the assistant in Settings.
        isAssistant = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    /** The assistant role cannot be requested with a dialog; the user picks it in Settings. */
    private fun openAssistantSettings() {
        for (action in listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) {
            try {
                startActivity(Intent(action))
                return
            } catch (_: ActivityNotFoundException) {
                // Try the next screen.
            }
        }
    }
}
