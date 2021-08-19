package com.dji.droneparking.environment

import android.os.Build

inline fun <T> sdk29andUp(onSdk29: () -> T): T? {
    return if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
        onSdk29()
    } else null
}