package com.misha.holdtranslate

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.util.TypedValue
import java.util.Locale

fun Context.forcedEnglish(): Context {
    val locale = Locale.ENGLISH
    Locale.setDefault(locale)
    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList(locale))
    return createConfigurationContext(config)
}

fun Context.dp(value: Int): Int {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()
}

fun Context.dpF(value: Float): Float {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics,
    )
}
