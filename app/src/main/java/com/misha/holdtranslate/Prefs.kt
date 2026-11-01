package com.misha.holdtranslate

import android.content.Context

internal object Prefs {
    private const val FILE = "hold_translate"
    private const val BUTTON_ON = "button_on"
    private const val MODEL_READY = "model_ready"
    private const val BUTTON_X = "button_x"
    private const val BUTTON_Y = "button_y"
    private const val API_KEY = "elevenlabs_api_key"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun buttonOn(context: Context): Boolean = prefs(context).getBoolean(BUTTON_ON, true)

    fun setButtonOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(BUTTON_ON, on).apply()
    }

    fun modelReady(context: Context): Boolean = prefs(context).getBoolean(MODEL_READY, false)

    fun setModelReady(context: Context, ready: Boolean) {
        prefs(context).edit().putBoolean(MODEL_READY, ready).apply()
    }

    fun buttonPosition(context: Context): Pair<Int, Int>? {
        val stored = prefs(context)
        if (!stored.contains(BUTTON_X) || !stored.contains(BUTTON_Y)) return null
        return stored.getInt(BUTTON_X, 0) to stored.getInt(BUTTON_Y, 0)
    }

    fun setButtonPosition(context: Context, x: Int, y: Int) {
        prefs(context).edit().putInt(BUTTON_X, x).putInt(BUTTON_Y, y).apply()
    }

    fun apiKey(context: Context): String = prefs(context).getString(API_KEY, "").orEmpty()

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(API_KEY, key.trim()).apply()
    }
}
