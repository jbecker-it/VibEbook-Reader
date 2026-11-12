package de.folio.reader.ui.reader

import android.view.KeyEvent

/** Only active in the reader WebView, never in account/password input fields. */
internal object ReaderKeys {
    fun command(key: Int, menuVisible: Boolean, volumeKeys: Boolean): String? = when (key) {
        KeyEvent.KEYCODE_PAGE_DOWN -> "next"
        KeyEvent.KEYCODE_PAGE_UP -> "prev"
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (!menuVisible) "next" else null
        KeyEvent.KEYCODE_DPAD_LEFT -> if (!menuVisible) "prev" else null
        KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeys && !menuVisible) "next" else null
        KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeys && !menuVisible) "prev" else null
        else -> null
    }
}
