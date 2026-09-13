package com.therealsoftware.duo

import android.content.Context

/**
 * The things a pair of phones works out once and should not have to work out
 * again: how far apart the two screens sit, how large each phone draws its half,
 * which way up they are, and which channel they talk on.
 *
 * They are kept on the phone, so a pair that was set up correctly tonight is
 * still set up correctly tomorrow. Nobody has to remember a number between one
 * evening and the next.
 *
 * The size belongs to the phone that holds it. The other three describe the
 * pair, and each phone keeps its own copy of what it last used — so either phone
 * can be the host next time and the pair keeps its settings.
 */
class Settings(context: Context) {

    private val store = context.getSharedPreferences("duo", Context.MODE_PRIVATE)

    var channel: Int
        get() = store.getInt("channel", 0)
        set(v) = store.edit().putInt("channel", v).apply()

    var gapMm: Float
        get() = store.getFloat("gapMm", 3f)
        set(v) = store.edit().putFloat("gapMm", v).apply()

    var calib: Float
        get() = store.getFloat("calib", 1f)
        set(v) = store.edit().putFloat("calib", v).apply()

    /** True when the host takes the left half. The host chooses it. */
    var hostFirst: Boolean
        get() = store.getBoolean("hostFirst", true)
        set(v) = store.edit().putBoolean("hostFirst", v).apply()

    var axis: Axis
        get() = if (store.getString("axis", null) == Axis.Vertical.name) {
            Axis.Vertical
        } else {
            Axis.Horizontal
        }
        set(v) = store.edit().putString("axis", v.name).apply()
}
