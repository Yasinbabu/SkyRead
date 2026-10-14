package fulguris.js

import com.anthonycr.mezzanine.FileStream

/**
 * JavaScript-based auto-scrolling for the Read Aloud feature.
 * Scrolls the page smoothly to keep the current reading position visible.
 */
@FileStream("src/main/js/ReadAloudAutoScroll.js")
interface ReadAloudAutoScroll {
    fun provideJs(): String
}
