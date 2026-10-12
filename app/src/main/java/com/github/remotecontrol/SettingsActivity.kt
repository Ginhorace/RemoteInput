package com.github.remotecontrol

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.remotecontrol.ui.SettingsScreen
import com.github.remotecontrol.ui.theme.RemoteInputTheme
import com.github.remotecontrol.util.AppPreferences
import com.github.remotecontrol.util.LocaleHelper
import com.github.remotecontrol.util.ThemeMode
import com.github.remotecontrol.viewmodel.SettingsViewModel

class SettingsActivity : ComponentActivity() {

    private var langVersion by mutableIntStateOf(0)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = AppPreferences.getInstance(this)
        langVersion = prefs.languageMode.ordinal

        setContent {
            val viewModel: SettingsViewModel = viewModel()
            val themeMode by viewModel.themeMode.collectAsState()
            val hueScheme by viewModel.hueScheme.collectAsState()
            val languageMode by viewModel.languageMode.collectAsState()
            val keyboardLayoutMode by viewModel.keyboardLayoutMode.collectAsState()
            val disabledCharsets by viewModel.disabledCharsets.collectAsState()
            val customCharsets by viewModel.customCharsets.collectAsState()

            val darkTheme = remember(themeMode) {
                when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemDarkTheme()
                    ThemeMode.DARK -> true
                    ThemeMode.LIGHT -> false
                }
            }

            RemoteInputTheme(
                darkTheme = darkTheme,
                hue = hueScheme
            ) {
                SettingsScreen(
                    onBackClick = { finish() },
                    themeMode = themeMode,
                    onThemeModeChange = viewModel::updateThemeMode,
                    hueScheme = hueScheme,
                    onHueSchemeChange = viewModel::updateHueScheme,
                    languageMode = languageMode,
                    onLanguageModeChange = { mode ->
                        viewModel.updateLanguageMode(mode)
                        recreate()
                    },
                    keyboardLayoutMode = keyboardLayoutMode,
                    // 宿主布局不涉及资源配置，改完不需要 recreate；ControlActivity 侧
                    // 在 onResume 会读到新值
                    onKeyboardLayoutModeChange = viewModel::updateKeyboardLayoutMode,
                    disabledCharsets = disabledCharsets,
                    customCharsets = customCharsets,
                    // 字符集同样只影响编辑器下拉，写进偏好即可，不需要 recreate
                    onCharsetEnabledChange = viewModel::setCharsetEnabled,
                    onAddCharset = viewModel::addCharset,
                    onRemoveCharset = viewModel::removeCharset,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = AppPreferences.getInstance(this)
        val newLangVersion = prefs.languageMode.ordinal
        if (newLangVersion != langVersion) {
            langVersion = newLangVersion
            recreate()
        }
    }

    private fun isSystemDarkTheme(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }
}