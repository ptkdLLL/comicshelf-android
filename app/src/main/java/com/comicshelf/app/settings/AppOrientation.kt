package com.comicshelf.app.settings

import android.content.pm.ActivityInfo
import com.comicshelf.app.core.CsSettings

enum class AppOrientation(
    val setting: String,
    val label: String,
    val request: Int,
) {
    SYSTEM("system", "跟随系统", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED),
    PORTRAIT("portrait", "竖屏", ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT),
    LANDSCAPE("landscape", "横屏", ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);

    fun save() {
        CsSettings.set(KEY, setting)
        CsSettings.save()
    }

    companion object {
        private const val KEY = "app_screen_orientation"

        internal fun fromSetting(value: String) = entries.firstOrNull { it.setting == value } ?: SYSTEM

        fun load() = fromSetting(CsSettings[KEY, SYSTEM.setting])
    }
}
