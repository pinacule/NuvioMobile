package com.nuvio.app.features.home.components

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class ContinueWatchingLayoutTest {

    @Test
    fun `two cards fit exactly on phones with no peeking third card`() {
        // 390dp phone: (390 - 2*16 padding - 16 gap) / 2 = 171 per card,
        // so 171*2 + 16 + 32 = 390 fills the row exactly.
        assertEquals(171.dp, continueWatchingTwoUpCardWidth(390f, 16.dp, 16.dp, 280.dp))
        assertEquals(156.dp, continueWatchingTwoUpCardWidth(360f, 16.dp, 16.dp, 280.dp))
    }

    @Test
    fun `desktop and tablet keep their designed widths`() {
        // On larger screens two designed cards already fit, so the cap wins.
        assertEquals(400.dp, continueWatchingTwoUpCardWidth(1440f, 32.dp, 20.dp, 400.dp))
        assertEquals(350.dp, continueWatchingTwoUpCardWidth(1024f, 28.dp, 18.dp, 350.dp))
        assertEquals(320.dp, continueWatchingTwoUpCardWidth(768f, 24.dp, 16.dp, 320.dp))
    }

    @Test
    fun `tiny screens get a usable minimum card width`() {
        assertEquals(96.dp, continueWatchingTwoUpCardWidth(200f, 16.dp, 16.dp, 280.dp))
    }
}
