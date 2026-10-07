package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceholderStreamPolicyTest {
    private fun minutes(value: Int) = value * 60_000L

    @Test
    fun `a short clip for a feature length title is a placeholder`() {
        assertTrue(PlaceholderStreamPolicy.isPlaceholder(durationMs = 30_000L, expectedRuntimeMs = minutes(120)))
        assertTrue(PlaceholderStreamPolicy.isPlaceholder(durationMs = minutes(3) - 1, expectedRuntimeMs = minutes(45)))
    }

    @Test
    fun `both the absolute and relative thresholds must agree`() {
        // Under 33% of a 120 min title, but a plausible 10 min file is not an error card.
        assertFalse(PlaceholderStreamPolicy.isPlaceholder(minutes(10), minutes(120)))
        // Exactly the absolute limit is accepted.
        assertFalse(PlaceholderStreamPolicy.isPlaceholder(minutes(3), minutes(120)))
        // Under 3 min but not under a third of a 20 min title only when the title is that short.
        assertTrue(PlaceholderStreamPolicy.isPlaceholder(minutes(3) - 1, minutes(20)))
    }

    @Test
    fun `titles without a long known runtime are never judged`() {
        assertFalse(PlaceholderStreamPolicy.isPlaceholder(30_000L, expectedRuntimeMs = null))
        assertFalse(PlaceholderStreamPolicy.isPlaceholder(30_000L, expectedRuntimeMs = minutes(19)))
    }

    @Test
    fun `an unknown duration is never a placeholder`() {
        assertFalse(PlaceholderStreamPolicy.isPlaceholder(0L, minutes(120)))
        assertFalse(PlaceholderStreamPolicy.isPlaceholder(-1L, minutes(120)))
    }

    @Test
    fun `a full length file passes`() {
        assertFalse(PlaceholderStreamPolicy.isPlaceholder(minutes(118), minutes(120)))
        assertFalse(PlaceholderStreamPolicy.isPlaceholder(minutes(44), minutes(45)))
    }

    @Test
    fun `expected runtime prefers the episode and falls back to the title text`() {
        assertEquals(minutes(42), PlaceholderStreamPolicy.expectedRuntimeMs(42, "60 min"))
        assertEquals(minutes(148), PlaceholderStreamPolicy.expectedRuntimeMs(null, "148 min"))
        assertEquals(minutes(130), PlaceholderStreamPolicy.expectedRuntimeMs(0, "2h 10min"))
        assertNull(PlaceholderStreamPolicy.expectedRuntimeMs(null, null))
        assertNull(PlaceholderStreamPolicy.expectedRuntimeMs(null, "TBA"))
        assertNull(PlaceholderStreamPolicy.expectedRuntimeMs(null, "0 min"))
    }
}
