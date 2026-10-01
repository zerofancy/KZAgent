package com.kzagent.kagent.desktop

import com.kzagent.kagent.desktop.app.loadAppIcons
import com.kzagent.kagent.desktop.app.selectTaskbarIcon
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopAppIconTest {
    @Test
    fun bundledDockIconRetainsFullResolutionAlongsideWindowIcons() {
        val icons = loadAppIcons()
        val dockIcon = requireNotNull(selectTaskbarIcon(icons))

        assertEquals(1024, dockIcon.getWidth(null))
        assertEquals(1024, dockIcon.getHeight(null))
        assertTrue(icons.any { it.getWidth(null) == 16 && it.getHeight(null) == 16 })
    }

    @Test
    fun dockIconSelectionDoesNotDependOnWindowIconOrder() {
        val small = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val large = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)
        val medium = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)

        assertSame(large, selectTaskbarIcon(listOf(small, large, medium)))
        assertSame(large, selectTaskbarIcon(listOf(large, medium, small)))
    }

    @Test
    fun missingIconsSkipTaskbarOverride() {
        assertNull(selectTaskbarIcon(emptyList()))
    }
}
