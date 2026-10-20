package com.nuvio.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.LocalNuvioBottomNavigationOverlayPadding
import com.nuvio.app.core.ui.LocalNuvioNavBarScrollState
import com.nuvio.app.core.ui.NuvioNavBarScrollState
import com.nuvio.app.core.ui.NuvioClassicNavigationBar
import com.nuvio.app.core.ui.FloatingNavigationBar
import com.nuvio.app.core.ui.FloatingNavigationItem
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.rememberNuvioNavBarScrollState
import com.nuvio.app.features.profiles.ActiveProfileMiniAvatar
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileSwitcherTab
import com.nuvio.app.features.settings.NavBarStyle
import com.nuvio.app.features.settings.ThemeSettingsRepository
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_nav_home
import nuvio.composeapp.generated.resources.compose_nav_library
import nuvio.composeapp.generated.resources.compose_nav_search
import nuvio.composeapp.generated.resources.sidebar_library
import nuvio.composeapp.generated.resources.sidebar_search
import org.jetbrains.compose.resources.stringResource

/**
 * Tabs shown in the bottom navigation bars (floating and classic), in
 * left-to-right order. Profile is not a bar tab: it is reached from the
 * profile button overlaid on the Home screen, which opens the
 * [AppScreenTab.Settings] destination.
 */
internal val MainBottomBarTabs: List<AppScreenTab> = listOf(
    AppScreenTab.Home,
    AppScreenTab.Library,
    AppScreenTab.Search,
)

@Composable
internal fun MainTabsDestination(
    selectedTab: AppScreenTab,
    initialHomeReady: Boolean,
    rootRouteActive: Boolean,
    useTabletFloatingTabBar: Boolean,
    useNativeNavigation: Boolean,
    useNativeTabBar: Boolean,
    liquidGlassNativeTabBarSupported: Boolean,
    liquidGlassNativeTabBarEnabled: Boolean,
    requests: AppTabRequests,
    state: AppTabState,
    actions: (isTabletLayout: Boolean) -> AppTabActions,
    onBack: () -> Unit,
    onTabSelected: (AppScreenTab) -> Unit,
    onProfileSelected: (NuvioProfile) -> Unit,
    onAddProfileRequested: () -> Unit,
) {
    PlatformBackHandler(enabled = rootRouteActive, onBack = onBack)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isTabletLayout = useTabletFloatingTabBar || maxWidth >= 768.dp
        val tabActions = remember(actions, isTabletLayout) { actions(isTabletLayout) }
        val useNativeBottomTabs = if (useNativeNavigation) {
            useNativeTabBar
        } else {
            liquidGlassNativeTabBarSupported && liquidGlassNativeTabBarEnabled && initialHomeReady
        }
        val tabsRouteActive = rootRouteActive
        val navBarScrollState = rememberNuvioNavBarScrollState()
        val navBarHazeState = rememberHazeState()
        val navBarStyleSetting by remember { ThemeSettingsRepository.navBarStyle }.collectAsStateWithLifecycle()
        val navBarGlowEnabled by ThemeSettingsRepository.navBarGlowEnabled.collectAsStateWithLifecycle()
        val floatingNavigationItems = MainBottomBarTabs.map { tab ->
            val itemSelected = selectedTab == tab
            val itemOnClick = { onTabSelected(tab) }
            when (tab) {
                AppScreenTab.Home -> FloatingNavigationItem(
                    selected = itemSelected,
                    onClick = itemOnClick,
                    icon = Icons.Filled.Home,
                    label = stringResource(Res.string.compose_nav_home),
                )
                AppScreenTab.Library -> FloatingNavigationItem(
                    selected = itemSelected,
                    onClick = itemOnClick,
                    drawable = Res.drawable.sidebar_library,
                    label = stringResource(Res.string.compose_nav_library),
                )
                AppScreenTab.Search -> FloatingNavigationItem(
                    selected = itemSelected,
                    onClick = itemOnClick,
                    drawable = Res.drawable.sidebar_search,
                    label = stringResource(Res.string.compose_nav_search),
                )
                AppScreenTab.Settings -> error("Settings is not a bottom bar tab")
            }
        }

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (initialHomeReady) 1f else 0f),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (!isTabletLayout && !useNativeBottomTabs && navBarStyleSetting == NavBarStyle.CLASSIC) {
                    NuvioClassicNavigationBar {
                        MainBottomBarTabs.forEach { tab ->
                            when (tab) {
                                AppScreenTab.Home -> NavItem(
                                    selected = selectedTab == tab,
                                    onClick = { onTabSelected(tab) },
                                    icon = Icons.Filled.Home,
                                    contentDescription = stringResource(Res.string.compose_nav_home),
                                )
                                AppScreenTab.Library -> NavItem(
                                    selected = selectedTab == tab,
                                    onClick = { onTabSelected(tab) },
                                    icon = Res.drawable.sidebar_library,
                                    contentDescription = stringResource(Res.string.compose_nav_library),
                                )
                                AppScreenTab.Search -> NavItem(
                                    selected = selectedTab == tab,
                                    onClick = { onTabSelected(tab) },
                                    icon = Res.drawable.sidebar_search,
                                    contentDescription = stringResource(Res.string.compose_nav_search),
                                )
                                AppScreenTab.Settings -> Unit
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(
                    LocalNuvioBottomNavigationOverlayPadding provides if (useNativeBottomTabs) 49.dp else if (!isTabletLayout && navBarStyleSetting != NavBarStyle.CLASSIC) 72.dp else 0.dp,
                    LocalNuvioNavBarScrollState provides navBarScrollState,
                ) {
                    AppTabHost(
                        selectedTab = selectedTab,
                        requests = requests,
                        state = state,
                        actions = tabActions,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (isTabletLayout || navBarStyleSetting != NavBarStyle.CLASSIC) Modifier.hazeSource(state = navBarHazeState) else Modifier)
                            .then(if (navBarStyleSetting == NavBarStyle.ADAPTIVE) Modifier.nestedScroll(navBarScrollState.nestedScrollConnection) else Modifier)
                            .padding(innerPadding),
                    )
                }

                if (isTabletLayout && !useNativeBottomTabs) {
                    val tabletNavBarScrollState = remember { NuvioNavBarScrollState().apply { collapse() } }
                    FloatingNavigationBar(
                        modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 416.dp),
                        scrollState = tabletNavBarScrollState,
                        hazeState = navBarHazeState,
                        contentPadding = PaddingValues(
                            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 10.dp,
                            bottom = 8.dp,
                        ),
                        compactSize = true,
                        items = floatingNavigationItems,
                        glowEnabled = navBarGlowEnabled,
                    )
                }

                if (!isTabletLayout && !useNativeBottomTabs && navBarStyleSetting != NavBarStyle.CLASSIC) {
                    when (navBarStyleSetting) {
                        NavBarStyle.EXPANDED -> navBarScrollState.expand()
                        NavBarStyle.COMPACT -> navBarScrollState.collapse()
                        else -> {}
                    }
                    FloatingNavigationBar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        scrollState = navBarScrollState,
                        hazeState = navBarHazeState,
                        items = floatingNavigationItems,
                        glowEnabled = navBarGlowEnabled,
                    )
                }

                // Profile lives on Home now: a circular button floating over
                // the hero, below the status bar. Only shown while a Compose
                // bar is in use — with native navigation/tab bars the host
                // provides its own profile tab and switcher.
                if (
                    selectedTab == AppScreenTab.Home &&
                    rootRouteActive &&
                    !useNativeNavigation &&
                    !useNativeBottomTabs
                ) {
                    HomeProfileButton(
                        onClick = { onTabSelected(AppScreenTab.Settings) },
                        onProfileSelected = onProfileSelected,
                        onAddProfileRequested = onAddProfileRequested,
                        hazeState = navBarHazeState,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(
                                top = WindowInsets.statusBars.asPaddingValues()
                                    .calculateTopPadding() + 12.dp,
                                end = 16.dp,
                            ),
                    )
                }
            }
        }
    }
}

/**
 * Circular profile button overlaid on the Home hero. Tapping opens the
 * Profile (Settings) destination; long-pressing keeps the profile switcher
 * popup the old Profile tab had. The avatar shows the active profile's
 * custom on-device picture when one is set, then the regular avatar,
 * then the initial letter.
 */
@Composable
private fun HomeProfileButton(
    onClick: () -> Unit,
    onProfileSelected: (NuvioProfile) -> Unit,
    onAddProfileRequested: () -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    ProfileSwitcherTab(
        selected = false,
        onClick = onClick,
        onProfileSelected = onProfileSelected,
        onAddProfileRequested = onAddProfileRequested,
        hazeState = hazeState,
        popupBelowAnchor = true,
        modifier = modifier,
        triggerContent = {
            val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
            val avatars by AvatarRepository.avatars.collectAsStateWithLifecycle()
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .shadow(tokens.elevation.overlay, CircleShape)
                    .clip(CircleShape)
                    .background(tokens.colors.surfaceSheet.copy(alpha = 0.92f))
                    .border(1.dp, tokens.colors.borderDefault, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ActiveProfileMiniAvatar(
                    profile = profileState.activeProfile,
                    avatars = avatars,
                    selected = false,
                    size = 34,
                )
            }
        },
    )
}
