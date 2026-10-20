package com.nuvio.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MainBottomBarTabsTest {

    @Test
    fun bottomBarShowsHomeLibrarySearchInOrder() {
        assertEquals(
            listOf(AppScreenTab.Home, AppScreenTab.Library, AppScreenTab.Search),
            MainBottomBarTabs,
        )
    }

    @Test
    fun bottomBarHasExactlyThreeTabsAndNoProfileTab() {
        assertEquals(3, MainBottomBarTabs.size)
        assertFalse(AppScreenTab.Settings in MainBottomBarTabs)
    }

    @Test
    fun librarySitsInTheMiddleOfTheBar() {
        assertEquals(AppScreenTab.Library, MainBottomBarTabs[1])
    }
}
