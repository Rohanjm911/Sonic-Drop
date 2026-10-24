package com.sonicdrop.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode {
    SYSTEM, DARK, LIGHT
}

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sonicdrop_settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        try {
            AppThemeMode.valueOf(prefs.getString("theme_mode", AppThemeMode.DARK.name) ?: AppThemeMode.DARK.name)
        } catch (e: Exception) {
            AppThemeMode.DARK
        }
    )
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _markFreq = MutableStateFlow(prefs.getFloat("mark_freq", 18600.0f))
    val markFreq: StateFlow<Float> = _markFreq.asStateFlow()

    private val _spaceFreq = MutableStateFlow(prefs.getFloat("space_freq", 19200.0f))
    val spaceFreq: StateFlow<Float> = _spaceFreq.asStateFlow()

    fun setThemeMode(mode: AppThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    fun setFrequencies(mark: Float, space: Float) {
        _markFreq.value = mark
        _spaceFreq.value = space
        prefs.edit().putFloat("mark_freq", mark).putFloat("space_freq", space).apply()
    }
}
