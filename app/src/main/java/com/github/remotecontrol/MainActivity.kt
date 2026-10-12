package com.github.remotecontrol

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.github.remotecontrol.ui.BluetoothScannerScreen
import com.github.remotecontrol.ui.theme.LocalAppTokens
import com.github.remotecontrol.ui.theme.RemoteInputTheme
import com.github.remotecontrol.util.AppPreferences
import com.github.remotecontrol.util.HueScheme
import com.github.remotecontrol.util.LocaleHelper
import com.github.remotecontrol.util.ThemeMode

class MainActivity : ComponentActivity() {

    private var isDark by mutableStateOf(false)
    private var hue by mutableStateOf(HueScheme.AZURE)
    private var langVersion by mutableIntStateOf(0)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = AppPreferences.getInstance(this)
        isDark = when (prefs.themeMode) {
                ThemeMode.SYSTEM -> isSystemDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
        hue = prefs.hueScheme
        langVersion = prefs.languageMode.ordinal
        Log.d("BluetoothHid", "hue: $hue")

        setContent {
            RemoteInputTheme(
                darkTheme = isDark,
                hue = hue
            ) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.app_name)) },
                            colors = TopAppBarDefaults.topAppBarColors(
                                // 顶栏用第 1 档（色相 10%/12%），和内容区拉开成"外壳"层
                                containerColor = LocalAppTokens.current.appBar,
                                titleContentColor = MaterialTheme.colorScheme.onSurface,
                                actionIconContentColor = LocalAppTokens.current.accent,
                            ),
                            actions = {
                                IconButton(onClick = {
                                    val intent = Intent(this@MainActivity, SettingsActivity::class.java)
                                    startActivity(intent)
                                }) {
                                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    BluetoothScannerScreen(modifier = Modifier.padding(paddingValues))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = AppPreferences.getInstance(this)
        val newDark = when (prefs.themeMode) {
                ThemeMode.SYSTEM -> isSystemDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
        val newHue = prefs.hueScheme
        if (newDark != isDark || newHue != hue) {
            isDark = newDark
            hue = newHue
        }
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