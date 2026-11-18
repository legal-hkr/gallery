@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import sh.hnet.gallery.R
import sh.hnet.gallery.about.WhatsNew
import sh.hnet.gallery.ui.settings.AboutScreen
import sh.hnet.gallery.ui.settings.AppLicenseScreen
import sh.hnet.gallery.ui.settings.LanguageScreen
import sh.hnet.gallery.ui.settings.LicensesScreen
import sh.hnet.gallery.ui.settings.ResetScreen
import sh.hnet.gallery.ui.settings.SettingsRootScreen
import sh.hnet.gallery.ui.settings.WhatsNewScreen

/** The app's shell (as Music's): one back stack per navigation bar destination, snackbars. */
@Composable
fun GalleryApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.PHOTOS) }
    val stacks: Map<Tab, NavBackStack<NavKey>> = Tab.entries.associateWith { rememberNavBackStack(it.root) }
    // Kept here, outside the tab's content, so a tab's pages keep their state while another tab shows
    val decorators = Tab.entries.associateWith { rememberSaveableStateHolderNavEntryDecorator<NavKey>() }
    val stack = stacks.getValue(tab)

    // A destination's root goes back to Photos, Photos' root leaves the app
    BackHandler(enabled = tab != Tab.PHOTOS && stack.size == 1) { tab = Tab.PHOTOS }

    val snackbars = remember { SnackbarHostState() }
    // After an update, once: "Updated to x.y.z" with What's new (as ComfyChair)
    val updatedTo = stringResource(R.string.whats_new_updated_snackbar)
    val whatsNew = stringResource(R.string.whats_new_title)
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        val version = WhatsNew.takeUpdateNotice(appContext) ?: return@LaunchedEffect
        if (snackbars.showSnackbar(updatedTo.format(version), actionLabel = whatsNew, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
            tab = Tab.SETTINGS
            val settings = stacks.getValue(Tab.SETTINGS)
            while (settings.size > 1) settings.removeAt(settings.lastIndex)
            settings.add(Screen.SettingsAbout)
            settings.add(Screen.SettingsWhatsNew)
        }
    }

    val motion = MaterialTheme.motionScheme
    val spatial: FiniteAnimationSpec<IntOffset> = motion.defaultSpatialSpec()
    val effects: FiniteAnimationSpec<Float> = motion.defaultEffectsSpec()
    val fastEffects: FiniteAnimationSpec<Float> = motion.fastEffectsSpec()
    val shift = with(LocalDensity.current) { 30.dp.roundToPx() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = {
            SnackbarHost(snackbars) { data ->
                Snackbar(data, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 12.dp))
            }
        },
        bottomBar = {
            ShortNavigationBar {
                Tab.entries.forEach { item ->
                    val selected = item == tab
                    ShortNavigationBarItem(
                        selected = selected,
                        onClick = {
                            // Re-selecting a destination returns to its root
                            if (selected) while (stack.size > 1) stack.removeAt(stack.lastIndex) else tab = item
                        },
                        icon = { Icon(painterResource(if (selected) item.selectedIcon else item.icon), contentDescription = null) },
                        label = { Text(stringResource(item.label), maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            // Fade through between destinations
            transitionSpec = { (fadeIn(effects) + scaleIn(initialScale = 0.92f)) togetherWith fadeOut(fastEffects) },
            // The bottom bar's height is padding already given: the keyboard only adds what it covers beyond it
            modifier = Modifier.fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
                .consumeWindowInsets(PaddingValues(bottom = padding.calculateBottomPadding())),
            label = "destination",
        ) { current ->
            val currentStack = stacks.getValue(current)
            val back: () -> Unit = { currentStack.removeAt(currentStack.lastIndex) }
            NavDisplay(
                backStack = currentStack,
                onBack = { if (currentStack.size > 1) currentStack.removeAt(currentStack.lastIndex) },
                entryDecorators = listOf(decorators.getValue(current)),
                // Shared axis X between a list and its pages
                transitionSpec = {
                    (slideInHorizontally(spatial) { shift } + fadeIn(effects)) togetherWith
                        (slideOutHorizontally(spatial) { -shift } + fadeOut(fastEffects))
                },
                popTransitionSpec = {
                    (slideInHorizontally(spatial) { -shift } + fadeIn(effects)) togetherWith
                        (slideOutHorizontally(spatial) { shift } + fadeOut(fastEffects))
                },
                predictivePopTransitionSpec = {
                    (slideInHorizontally(spatial) { -shift } + fadeIn(effects)) togetherWith
                        (slideOutHorizontally(spatial) { shift } + fadeOut(fastEffects))
                },
                entryProvider = entryProvider {
                    entry<Screen.Photos> { PlaceholderScreen(stringResource(R.string.label_nav_photos)) }
                    entry<Screen.Albums> { PlaceholderScreen(stringResource(R.string.label_nav_albums)) }
                    entry<Screen.Search> { PlaceholderScreen(stringResource(R.string.label_nav_search)) }
                    entry<Screen.Settings> {
                        SettingsRootScreen(
                            onOpenLanguage = { currentStack.add(Screen.SettingsLanguage) },
                            onOpenReset = { currentStack.add(Screen.SettingsReset) },
                            onOpenAbout = { currentStack.add(Screen.SettingsAbout) },
                        )
                    }
                    entry<Screen.SettingsAbout> {
                        AboutScreen(
                            onBack = back,
                            onOpenWhatsNew = { currentStack.add(Screen.SettingsWhatsNew) },
                            onOpenLicense = { currentStack.add(Screen.SettingsLicense) },
                            onOpenLicenses = { currentStack.add(Screen.SettingsLicenses) },
                        )
                    }
                    entry<Screen.SettingsLicenses> { LicensesScreen(onBack = back) }
                    entry<Screen.SettingsLanguage> { LanguageScreen(onBack = back) }
                    entry<Screen.SettingsReset> { ResetScreen(onBack = back) }
                    entry<Screen.SettingsWhatsNew> { WhatsNewScreen(onBack = back) }
                    entry<Screen.SettingsLicense> { AppLicenseScreen(onBack = back) }
                },
            )
        }
    }
}
