package dev.alex.folderplayer

import android.content.Context

/** Shorthand for localized UI strings; keeps call sites compact. */
fun Context.afp(id: Int): String = getString(id)
