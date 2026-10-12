package com.github.remotecontrol.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {
    fun wrapContext(context: Context): Context {
        val appPrefs = AppPreferences.getInstance(context)
        val locale = appPrefs.languageMode.locale
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    /**
     * 当前界面语言。
     *
     * 读的是 AppPreferences 里用户选的语言模式，而不是 `resources.configuration`：
     * 后者只有在被 [wrapContext] 包过的 Activity Context 上才等于界面语言，
     * 而 `applicationContext`（store、后台组件拿到的那一份）的 configuration 永远是系统语言。
     * 需要「界面语言」的地方统一走这里，才不会出现「模板按系统语言算码页、界面却显示另一种语言」。
     */
    fun currentLocale(context: Context): Locale =
        AppPreferences.getInstance(context).languageMode.locale
}
