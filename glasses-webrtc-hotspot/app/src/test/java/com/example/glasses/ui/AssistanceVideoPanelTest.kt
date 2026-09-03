package com.example.glasses.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistanceVideoPanelTest {
    @Test
    fun glassesModeDefaultsToRawVideoAndDepth() {
        assertEquals(
            AssistanceVideoPanel.RAW_VIDEO.mask or AssistanceVideoPanel.DEPTH.mask,
            defaultAssistanceVideoPanelMask(usePhoneCamera = false),
        )
    }

    @Test
    fun phoneCameraModeDefaultsToDepthAndObstacle() {
        assertEquals(
            AssistanceVideoPanel.DEPTH.mask or AssistanceVideoPanel.OBSTACLE.mask,
            defaultAssistanceVideoPanelMask(usePhoneCamera = true),
        )
    }

    @Test
    fun selectingAThirdPanelKeepsTheTwoPanelLimit() {
        val current = AssistanceVideoPanel.RAW_VIDEO.mask or AssistanceVideoPanel.DEPTH.mask

        assertEquals(
            current,
            toggleAssistanceVideoPanel(
                currentMask = current,
                panel = AssistanceVideoPanel.OBSTACLE,
                availableMask = availableAssistanceVideoPanelMask(rawVideoAvailable = true),
            ),
        )
    }

    @Test
    fun deselectingOnePanelAllowsSelectingAnother() {
        val available = availableAssistanceVideoPanelMask(rawVideoAvailable = true)
        val initial = AssistanceVideoPanel.RAW_VIDEO.mask or AssistanceVideoPanel.DEPTH.mask
        val rawOnly = toggleAssistanceVideoPanel(
            currentMask = initial,
            panel = AssistanceVideoPanel.DEPTH,
            availableMask = available,
        )

        assertEquals(
            AssistanceVideoPanel.RAW_VIDEO.mask or AssistanceVideoPanel.OBSTACLE.mask,
            toggleAssistanceVideoPanel(
                currentMask = rawOnly,
                panel = AssistanceVideoPanel.OBSTACLE,
                availableMask = available,
            ),
        )
    }

    @Test
    fun lastVisiblePanelCanBeDeselected() {
        assertEquals(
            0,
            toggleAssistanceVideoPanel(
                currentMask = AssistanceVideoPanel.DEPTH.mask,
                panel = AssistanceVideoPanel.DEPTH,
                availableMask = availableAssistanceVideoPanelMask(rawVideoAvailable = false),
            ),
        )
    }

    @Test
    fun unavailableRawVideoCannotBeSelected() {
        val available = availableAssistanceVideoPanelMask(rawVideoAvailable = false)

        assertEquals(
            AssistanceVideoPanel.DEPTH.mask,
            toggleAssistanceVideoPanel(
                currentMask = AssistanceVideoPanel.DEPTH.mask,
                panel = AssistanceVideoPanel.RAW_VIDEO,
                availableMask = available,
            ),
        )
    }
}
