package com.kzagent.kagent.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsPanelCommandTest {
    @Test
    fun unavailableCommandInstallIsDisabled() {
        val presentation = userCommandButtonPresentation(
            available = false,
            installed = false,
            installing = false,
        )

        assertEquals("安装 kza 命令", presentation.text)
        assertTrue(presentation.disabled)
    }

    @Test
    fun installedCommandCanBeReinstalled() {
        val presentation = userCommandButtonPresentation(
            available = true,
            installed = true,
            installing = false,
        )

        assertEquals("重新安装 kza 命令", presentation.text)
        assertFalse(presentation.disabled)
    }

    @Test
    fun installationInProgressPreventsDuplicateClicks() {
        val presentation = userCommandButtonPresentation(
            available = true,
            installed = false,
            installing = true,
        )

        assertEquals("正在安装...", presentation.text)
        assertTrue(presentation.disabled)
    }
}
