package com.guidelens.app

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("guidelens", Context.MODE_PRIVATE)

    var audio: Boolean
        get() = sp.getBoolean("audio", true)
        set(v) { sp.edit().putBoolean("audio", v).apply() }

    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) { sp.edit().putBoolean("haptics", v).apply() }

    var stepFree: Boolean
        get() = sp.getBoolean("stepFree", false)
        set(v) { sp.edit().putBoolean("stepFree", v).apply() }

    var verbosity: String
        get() = sp.getString("verbosity", "standard") ?: "standard"
        set(v) { sp.edit().putString("verbosity", v).apply() }

    var sensitivity: String
        get() = sp.getString("sensitivity", "standard") ?: "standard"
        set(v) { sp.edit().putString("sensitivity", v).apply() }

    var rate: Float
        get() = sp.getFloat("rate", 1.0f)
        set(v) { sp.edit().putFloat("rate", v).apply() }

    var contact: String
        get() = sp.getString("contact", "") ?: ""
        set(v) { sp.edit().putString("contact", v).apply() }

    var themeDark: Boolean
        get() = sp.getBoolean("themeDark", true)
        set(v) { sp.edit().putBoolean("themeDark", v).apply() }

    var simple: Boolean
        get() = sp.getBoolean("simple", false)
        set(v) { sp.edit().putBoolean("simple", v).apply() }

    var agreed: Boolean
        get() = sp.getBoolean("agreed", false)
        set(v) { sp.edit().putBoolean("agreed", v).apply() }
}
