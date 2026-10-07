package de.folio.reader.ui.reader

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class ReaderKeysTest {
    @Test fun physicalPageKeysWorkWithoutOptIn() {
        assertEquals("next", ReaderKeys.command(KeyEvent.KEYCODE_PAGE_DOWN, false, false))
        assertEquals("prev", ReaderKeys.command(KeyEvent.KEYCODE_PAGE_UP, false, false))
    }
    @Test fun volumePagingRequiresOptInAndClosedMenu() {
        assertNull(ReaderKeys.command(KeyEvent.KEYCODE_VOLUME_DOWN, false, false))
        assertNull(ReaderKeys.command(KeyEvent.KEYCODE_VOLUME_DOWN, true, true))
        assertEquals("next", ReaderKeys.command(KeyEvent.KEYCODE_VOLUME_DOWN, false, true))
        assertEquals("prev", ReaderKeys.command(KeyEvent.KEYCODE_VOLUME_UP, false, true))
    }
    @Test fun menuKeepsDpadNavigation() {
        assertNull(ReaderKeys.command(KeyEvent.KEYCODE_DPAD_LEFT, true, false))
        assertEquals("prev", ReaderKeys.command(KeyEvent.KEYCODE_DPAD_LEFT, false, false))
        assertNull(ReaderKeys.command(KeyEvent.KEYCODE_A, false, false))
    }
}
