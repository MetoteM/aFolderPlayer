package dev.alex.folderplayer

import android.view.View

/** Page navigation: extracted from MainActivity (roadmap 4.2). */
internal fun MainActivity.backPage() {
        val target =
            when (page) {
                "player" -> "library"
                "sound" -> soundReturn
                "translation" -> "lyrics"
                else -> "player"
            }
        if (page == "player") {
            showPage(target)
            if(prefs.getBoolean("onlinePlayerOrigin",false))onlineSection.show()
        }
        else pageMotion.leavePage { if (alive) showPage(target) }
    }

internal fun MainActivity.previewPlayer(travel: Float) {
        if (prefs.getString("activeBook", "").orEmpty().isNotEmpty()) return
        if (page != "library") return
        if (screens.view.visibility != View.VISIBLE) {
            screens.show("player")
            updatePlayer()
            currentTrack()?.let { loadScreenMedia(it) }
        }
        pageMotion.preview(root.height, travel)
    }

internal fun MainActivity.showPage(value: String, animate: Boolean = true) {
        val before = page
        page =
            value.takeIf { it in setOf("player", "lyrics", "translation", "queue", "sound") }
                ?: "library"
        if (page != "translation") cancelTranslation()
        refreshTicker()
        if (page != "lyrics") {
            stopDirectLyrics()
            lyricsRequestedId = null
        }
        prefs.edit().putString("uiPage", page).apply()
        layout.visibility = View.VISIBLE
        layout.importantForAccessibility =
            if (page == "library") View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val revealStart =
            if (before == "library" && screens.view.visibility == View.VISIBLE)
                screens.view.translationY
            else null
        if (page != "library") {
            if (page == "queue") queueTimeline = null
            screens.show(page)
            updatePlayer()
        }
        pageMotion.show(
            page != "library",
            root.height,
            animate && before != page,
            before == "library",
            revealStart,
        )
        if (page == "translation") currentTrack()?.let { translate(it) }
    }

internal fun MainActivity.equalizer() {
        if (page != "sound") soundReturn = page
        showPage("sound")
    }

