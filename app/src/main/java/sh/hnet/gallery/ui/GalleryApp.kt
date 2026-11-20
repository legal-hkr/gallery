@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.about.WhatsNew
import sh.hnet.gallery.data.MediaWriter
import sh.hnet.gallery.model.CollectionKind
import sh.hnet.gallery.model.FolderKey
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.PickRequest
import sh.hnet.gallery.model.TimelineFolders
import sh.hnet.gallery.ui.albums.AddToAlbumSheet
import sh.hnet.gallery.ui.albums.AlbumPage
import sh.hnet.gallery.ui.albums.AlbumsScreen
import sh.hnet.gallery.ui.albums.PageKey
import sh.hnet.gallery.ui.albums.TrashScreen
import sh.hnet.gallery.ui.components.ConfirmDestructiveDialog
import sh.hnet.gallery.ui.components.ErrorDetails
import sh.hnet.gallery.ui.components.ErrorDetailsDialog
import sh.hnet.gallery.ui.components.LocalConsent
import sh.hnet.gallery.ui.components.LocalMediaActions
import sh.hnet.gallery.ui.components.LocalOpenAlbum
import sh.hnet.gallery.ui.components.MediaAccess
import sh.hnet.gallery.ui.components.MediaActions
import sh.hnet.gallery.ui.components.MediaManagement
import sh.hnet.gallery.ui.components.MediaManagementOfferSheet
import sh.hnet.gallery.ui.components.StatusCard
import sh.hnet.gallery.ui.components.placeData
import sh.hnet.gallery.ui.components.rememberConsent
import sh.hnet.gallery.ui.editor.EditorScreen
import sh.hnet.gallery.ui.grid.LocalHideNavigationBar
import sh.hnet.gallery.ui.map.MapScreen
import sh.hnet.gallery.ui.photos.PhotosScreen
import sh.hnet.gallery.ui.pick.PickerScreen
import sh.hnet.gallery.ui.places.CountryScreen
import sh.hnet.gallery.ui.places.PlacesScreen
import sh.hnet.gallery.ui.search.SearchScreen
import sh.hnet.gallery.ui.settings.AboutScreen
import sh.hnet.gallery.ui.settings.AppLicenseScreen
import sh.hnet.gallery.ui.settings.BackupScreen
import sh.hnet.gallery.ui.settings.LanguageScreen
import sh.hnet.gallery.ui.settings.LicensesScreen
import sh.hnet.gallery.ui.settings.PhotosSettingsScreen
import sh.hnet.gallery.ui.settings.PlaceDataScreen
import sh.hnet.gallery.ui.settings.PlacesSettingsScreen
import sh.hnet.gallery.ui.settings.ResetScreen
import sh.hnet.gallery.ui.settings.SettingsRootScreen
import sh.hnet.gallery.ui.settings.SharingSettingsScreen
import sh.hnet.gallery.ui.settings.SlideshowSettingsScreen
import sh.hnet.gallery.ui.settings.VaultSettingsScreen
import sh.hnet.gallery.ui.settings.ViewerSettingsScreen
import sh.hnet.gallery.ui.settings.WhatsNewScreen
import sh.hnet.gallery.ui.slideshow.LocalSlideshow
import sh.hnet.gallery.ui.slideshow.SlideshowRequest
import sh.hnet.gallery.ui.slideshow.SlideshowScreen
import sh.hnet.gallery.ui.vault.ExportDialog
import sh.hnet.gallery.ui.vault.ImportDialog
import sh.hnet.gallery.ui.vault.LocalVaultActions
import sh.hnet.gallery.ui.vault.VaultActions
import sh.hnet.gallery.ui.vault.VaultScreen
import sh.hnet.gallery.ui.viewer.LocalMediaOrigins
import sh.hnet.gallery.ui.viewer.MediaOrigins
import sh.hnet.gallery.ui.viewer.ViewerHost
import sh.hnet.gallery.ui.viewer.ViewerRequest
import sh.hnet.gallery.ui.welcome.WizardScreen

/**
 * The app's shell (as Music's, SPEC §3): one back stack per navigation bar destination, snackbars.
 * Pages pushed over a tab's root (Settings, an album) hide the navigation bar.
 */
@Composable
fun GalleryApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.PHOTOS) }
    val stacks: Map<Tab, NavBackStack<NavKey>> = Tab.entries.associateWith { rememberNavBackStack(it.root) }
    // Kept here, outside the tab's content, so a tab's pages keep their state while another tab shows; view models belong to their entry (as Contacts')
    val decorators = Tab.entries.associateWith { rememberSaveableStateHolderNavEntryDecorator<NavKey>() }
    val viewModels = Tab.entries.associateWith { rememberViewModelStoreNavEntryDecorator<NavKey>() }
    val stack = stacks.getValue(tab)

    // A destination's root goes back to Photos, Photos' root leaves the app
    BackHandler(enabled = tab != Tab.PHOTOS && stack.size == 1) { tab = Tab.PHOTOS }

    val snackbars = remember { SnackbarHostState() }
    // After an update, once: "Updated to x.y.z" with What's new (as ComfyChair)
    val updatedTo = stringResource(R.string.whats_new_updated_snackbar)
    val whatsNew = stringResource(R.string.whats_new_title)
    val appContext = LocalContext.current.applicationContext
    val app = appContext as GalleryApplication
    // Access may have been given or taken in the system meanwhile, and a partial selection changed (SPEC §19.4)
    LifecycleResumeEffect(Unit) {
        app.index.recheck()
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) {
        val version = WhatsNew.takeUpdateNotice(appContext) ?: return@LaunchedEffect
        if (snackbars.showSnackbar(updatedTo.format(version), actionLabel = whatsNew, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
            val current = stacks.getValue(tab)
            while (current.size > 1) current.removeAt(current.lastIndex)
            current.add(Screen.Settings)
            current.add(Screen.SettingsAbout)
            current.add(Screen.SettingsWhatsNew)
        }
    }

    val motion = MaterialTheme.motionScheme
    val spatial: FiniteAnimationSpec<IntOffset> = motion.defaultSpatialSpec()
    val effects: FiniteAnimationSpec<Float> = motion.defaultEffectsSpec()
    val fastEffects: FiniteAnimationSpec<Float> = motion.fastEffectsSpec()
    val shift = with(LocalDensity.current) { 30.dp.roundToPx() }

    val messages = rememberCoroutineScope()
    // The first run (SPEC §19): the wizard lies over everything until it is done, as Contacts'
    var wizard by rememberSaveable { mutableStateOf(!MediaAccess.wizardDone(appContext)) }
    // The viewer over everything, while open (LOGIKA §1); not kept over the process's death (SPEC §3.3)
    var viewer by remember { mutableStateOf<ViewerRequest?>(null) }
    // The editor over the viewer (S7, D24)
    var editing by remember { mutableStateOf<MediaItem?>(null) }
    var overViewer by remember { mutableStateOf<Screen?>(null) }
    // The slideshow over everything (S8a), the viewer under it when started from there
    var slideshow by remember { mutableStateOf<SlideshowRequest?>(null) }
    val origins = remember { MediaOrigins() }
    val consent = rememberConsent()
    val activity = LocalActivity.current
    val actions = remember(consent) { MediaActions(appContext, MediaWriter(appContext), consent, snackbars, messages) }
    // The vault's opening and moving in (S8b), from any grid and the viewer
    val shellActivity = LocalActivity.current
    val vaultActions = remember(consent, shellActivity) { shellActivity?.let { VaultActions(it, app.vault, MediaWriter(appContext), consent, actions, app.appScope) } }
    val moving by (vaultActions?.progress ?: MutableStateFlow(null)).collectAsStateWithLifecycle()
    var movingShown by remember { mutableStateOf(false) }
    LaunchedEffect(moving != null) {
        movingShown = false
        if (moving != null) {
            delay(1000)
            movingShown = true
        }
    }
    var errorShown by remember { mutableStateOf<ErrorDetails?>(null) }
    SideEffect {
        actions.start = { intent -> activity?.startActivity(intent) }
        actions.showError = { errorShown = it }
    }
    // A folder's album asked for by the outside viewer: on the Albums tab, over its root
    LaunchedEffect(Unit) {
        app.openFolder.filterNotNull().collect { key ->
            app.openFolder.value = null
            viewer = null
            tab = Tab.ALBUMS
            val albums = stacks.getValue(Tab.ALBUMS)
            while (albums.size > 1) albums.removeAt(albums.lastIndex)
            albums.add(Screen.Folder(key))
        }
    }
    // The share sheet's Include / Remove location: the same items again with the other choice (SPEC §14.2)
    LaunchedEffect(actions) {
        app.shareAgain.filterNotNull().collect { again ->
            app.shareAgain.value = null
            val byId = app.index.items.value.orEmpty().associateBy { it.id }
            actions.share(again.ids.mapNotNull { byId[it] }, again.removeLocation)
        }
    }
    // Preparing a share (SPEC §14.3): the status card once it takes over two seconds
    val shareProgress by app.sharing.progress.collectAsStateWithLifecycle()
    var shareShown by remember { mutableStateOf(false) }
    LaunchedEffect(shareProgress != null) {
        shareShown = false
        if (shareProgress != null) {
            delay(2000)
            shareShown = true
        }
    }
    // The second offer of Media management (SPEC §10.6): once, after the first system dialog the user confirmed
    val offers by app.preferences.mediaManagementOffers.collectAsStateWithLifecycle(2)
    var offering by remember { mutableStateOf(false) }
    val offersNow by rememberUpdatedState(offers)
    SideEffect {
        consent.onConfirmed = { if (offersNow < 2 && !MediaManagement.granted(appContext)) offering = true }
    }
    // A tab's root selecting items: the selection's toolbar takes the navigation bar's place (SPEC §5.1)
    val hideNavigationBar = remember { mutableStateOf(false) }

    // Reading the files' facts (owner's decision D4): the status card once a reading takes over a second, never for a single new shot
    val reading by app.facts.progress.collectAsStateWithLifecycle()
    var readingShown by remember { mutableStateOf(false) }
    LaunchedEffect(reading != null) {
        if (reading != null) {
            delay(1000)
            readingShown = true
        } else {
            readingShown = false
        }
    }

    CompositionLocalProvider(
        LocalMediaOrigins provides origins,
        LocalConsent provides consent,
        LocalMediaActions provides actions,
        LocalHideNavigationBar provides hideNavigationBar,
        // Show in a snackbar, a chip in Info: the album's page over the viewer when it is open, else on the current tab
        LocalOpenAlbum provides { id -> if (viewer != null) overViewer = Screen.Album(id) else stacks.getValue(tab).add(Screen.Album(id)) },
        LocalSlideshow provides { slideshow = it },
        LocalVaultActions provides vaultActions,
    ) {
    // Moving in with the vault not there (not set up, its key lost): its page on the current tab
    SideEffect { vaultActions?.openVault = { viewer = null; stacks.getValue(tab).add(Screen.Vault) } }
    // Import's files and export's folder through the system's pickers (SPEC §18.6–§18.7)
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vaultActions?.picked(uris) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree -> vaultActions?.folderPicked(tree) }
    SideEffect {
        vaultActions?.pickFiles = { importPicker.launch(arrayOf("image/*", "video/*")) }
        vaultActions?.pickFolder = { folderPicker.launch(null) }
    }
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = {
            if (!wizard && viewer == null) SnackbarHost(snackbars) { data ->
                Snackbar(data, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 12.dp))
            }
        },
        bottomBar = {
            // Only on a tab's root: pages pushed over it have Back (SPEC §9.4); while selecting, the selection's toolbar is there (as ComfyChair's)
            AnimatedVisibility(
                visible = stack.size == 1 && !hideNavigationBar.value,
                enter = expandVertically(expandFrom = Alignment.Top),
                exit = shrinkVertically(shrinkTowards = Alignment.Top),
            ) { ShortNavigationBar {
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
            } }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
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
                entryDecorators = listOf(decorators.getValue(current), viewModels.getValue(current)),
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
                    entry<Screen.Photos> {
                        PhotosScreen(
                            onOpenSettings = { currentStack.add(Screen.Settings) },
                            onChooseAlbums = {
                                currentStack.add(Screen.Settings)
                                currentStack.add(Screen.SettingsPhotos)
                            },
                            onOpen = { viewer = it },
                        )
                    }
                    entry<Screen.Albums> {
                        AlbumsScreen(
                            onOpenSettings = { currentStack.add(Screen.Settings) },
                            onOpenCollection = { currentStack.add(Screen.Collection(it.name)) },
                            onOpenFolder = { currentStack.add(Screen.Folder(it.key)) },
                            onOpenAlbum = { currentStack.add(Screen.Album(it)) },
                            onOpenTrash = { currentStack.add(Screen.Trash) },
                            onOpenPlaces = { currentStack.add(Screen.Places) },
                            onOpenVault = { currentStack.add(Screen.Vault) },
                            onNewAlbum = { id ->
                                currentStack.add(Screen.Album(id))
                                currentStack.add(Screen.PickForAlbum(id))
                            },
                        )
                    }
                    entry<Screen.Collection> { key -> AlbumPage(PageKey.Collection(CollectionKind.of(key.kind)), onBack = back, onOpen = { viewer = it }) }
                    entry<Screen.Album> { key -> AlbumPage(PageKey.User(key.id), onBack = back, onOpen = { viewer = it }, onAddPhotos = { currentStack.add(Screen.PickForAlbum(key.id)) }) }
                    entry<Screen.PickForAlbum> { key ->
                        val albums by app.albums.albums.collectAsStateWithLifecycle()
                        val name = albums?.firstOrNull { it.id == key.id }?.name.orEmpty()
                        // Done adds the chosen items and returns to the album; Close leaves it as it was (SPEC-albums §9.6)
                        PickerScreen(PickRequest.ALL, stringResource(R.string.title_pick_for_album, name), onClose = back, onLook = { viewer = it }, onDone = { items ->
                            app.appScope.launch { app.albums.add(key.id, items, System.currentTimeMillis()) }
                            back()
                        })
                    }
                    entry<Screen.Folder> { key ->
                        val folder = FolderKey.of(key.key)
                        if (folder != null) AlbumPage(PageKey.Folder(folder), onBack = back, onOpen = { viewer = it }) else LaunchedEffect(Unit) { back() }
                    }
                    entry<Screen.Trash> { TrashScreen(onBack = back, onOpen = { viewer = it }) }
                    entry<Screen.Vault> { VaultScreen(onBack = back, onOpen = { viewer = it }) }
                    entry<Screen.SettingsVault> { VaultSettingsScreen(onBack = back, onOpenVault = { currentStack.add(Screen.Vault) }) }
                    entry<Screen.Places> {
                        PlacesScreen(
                            onBack = back,
                            onOpenCountry = { country -> app.places.value?.let { currentStack.add(Screen.PlaceCountry(country, it.version)) } },
                            onOpenTown = { town -> app.places.value?.let { currentStack.add(Screen.PlaceTown(town, it.version)) } },
                            onOpenNoLocation = { currentStack.add(Screen.NoLocation) },
                            onOpenMap = { currentStack.add(Screen.Map()) },
                        )
                    }
                    // A page of other place data (an app update meanwhile) closes: its index names something else now
                    entry<Screen.PlaceCountry> { key ->
                        val data = placeData()
                        if (data != null && data.version != key.version) LaunchedEffect(Unit) { back() }
                        else CountryScreen(key.country, onBack = back, onOpenTown = { town -> currentStack.add(Screen.PlaceTown(town, key.version)) })
                    }
                    entry<Screen.PlaceTown> { key ->
                        val data = placeData()
                        if (data != null && data.version != key.version) LaunchedEffect(Unit) { back() }
                        else AlbumPage(PageKey.Town(key.settlement), onBack = back, onOpen = { viewer = it })
                    }
                    entry<Screen.NoLocation> { AlbumPage(PageKey.NoLocation, onBack = back, onOpen = { viewer = it }) }
                    entry<Screen.Map> { key -> MapScreen(onBack = back, onOpen = { viewer = it }, lat = key.lat, lon = key.lon) }
                    entry<Screen.Search> { SearchScreen(onOpen = { viewer = it }) }
                    entry<Screen.Settings> {
                        SettingsRootScreen(
                            onBack = back,
                            onOpenPhotos = { currentStack.add(Screen.SettingsPhotos) },
                            onOpenViewer = { currentStack.add(Screen.SettingsViewer) },
                            onOpenSharing = { currentStack.add(Screen.SettingsSharing) },
                            onOpenSlideshow = { currentStack.add(Screen.SettingsSlideshow) },
                            onOpenPlaces = { currentStack.add(Screen.SettingsPlaces) },
                            onOpenVault = { currentStack.add(Screen.SettingsVault) },
                            onOpenLanguage = { currentStack.add(Screen.SettingsLanguage) },
                            onOpenBackup = { currentStack.add(Screen.SettingsBackup) },
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
                            onOpenPlaceData = { currentStack.add(Screen.SettingsPlaceData) },
                        )
                    }
                    entry<Screen.SettingsPhotos> { PhotosSettingsScreen(onBack = back) }
                    entry<Screen.SettingsViewer> { ViewerSettingsScreen(onBack = back) }
                    entry<Screen.SettingsSharing> { SharingSettingsScreen(onBack = back) }
                    entry<Screen.SettingsSlideshow> { SlideshowSettingsScreen(onBack = back) }
                    entry<Screen.SettingsPlaces> { PlacesSettingsScreen(onBack = back) }
                    entry<Screen.SettingsBackup> { BackupScreen(onBack = back, snackbars = snackbars, messageScope = messages) }
                    entry<Screen.SettingsLicenses> { LicensesScreen(onBack = back) }
                    entry<Screen.SettingsPlaceData> { PlaceDataScreen(onBack = back) }
                    entry<Screen.SettingsLanguage> { LanguageScreen(onBack = back) }
                    entry<Screen.SettingsReset> { ResetScreen(onBack = back) }
                    entry<Screen.SettingsWhatsNew> { WhatsNewScreen(onBack = back) }
                    entry<Screen.SettingsLicense> { AppLicenseScreen(onBack = back) }
                },
            )
        }
        val sharingNow = shareProgress
        if (shareShown && sharingNow != null && !wizard) {
            StatusCard(
                title = stringResource(R.string.status_preparing_share),
                line = stringResource(R.string.progress_removing_location, sharingNow.done, sharingNow.total),
                progress = sharingNow.done.toFloat() / sharingNow.total.coerceAtLeast(1),
                onCancel = actions::cancelShare,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(if (stack.size == 1 && !hideNavigationBar.value) Modifier else Modifier.navigationBarsPadding())
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp + padding.calculateBottomPadding()),
            )
        }
        val movingNow = moving
        if (movingShown && movingNow != null && !wizard) {
            StatusCard(
                title = stringResource(
                    when (movingNow.kind) {
                        VaultActions.Kind.MOVING -> R.string.status_moving_to_vault
                        VaultActions.Kind.IMPORTING -> R.string.status_importing
                        VaultActions.Kind.EXPORTING -> R.string.status_exporting
                        VaultActions.Kind.TAKING_OUT -> R.string.status_taking_out_vault
                    },
                ),
                line = when (movingNow.kind) {
                    VaultActions.Kind.MOVING -> stringResource(R.string.progress_moved, movingNow.done, movingNow.total)
                    VaultActions.Kind.IMPORTING -> stringResource(R.string.progress_imported, movingNow.done, movingNow.total)
                    VaultActions.Kind.EXPORTING -> stringResource(R.string.progress_exported, movingNow.done, movingNow.total)
                    VaultActions.Kind.TAKING_OUT -> stringResource(R.string.progress_taken_out, movingNow.done, movingNow.total)
                },
                progress = movingNow.done.toFloat() / movingNow.total.coerceAtLeast(1),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(if (stack.size == 1 && !hideNavigationBar.value) Modifier else Modifier.navigationBarsPadding())
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp + padding.calculateBottomPadding()),
            )
        }
        val progress = reading
        if (readingShown && progress != null && !wizard && !(shareShown && sharingNow != null) && !(movingShown && movingNow != null)) {
            StatusCard(
                title = stringResource(R.string.status_reading),
                line = stringResource(R.string.progress_read, progress.done, progress.total),
                progress = progress.done.toFloat() / progress.total.coerceAtLeast(1),
                // Above the navigation bar on a tab's root (the content ends there), above the system's navigation on pages
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(if (stack.size == 1 && !hideNavigationBar.value) Modifier else Modifier.navigationBarsPadding())
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp + padding.calculateBottomPadding()),
            )
        }
        }
    }
    viewer?.let { request ->
        // A new opening (from a page over the viewer) is a new viewer, not this one's state over another list
        key(request) {
            ViewerHost(request, snackbars, messages, onClose = { viewer = null }, onOpenFolder = { item ->
                overViewer = if (TimelineFolders.isScreenshots(item.path)) Screen.Collection(CollectionKind.SCREENSHOTS.name) else Screen.Folder(item.folder.key)
            }, onOpenMap = { lat, lon -> overViewer = Screen.Map(lat, lon) }, onEdit = { editing = it })
        }
    }
    // An album opened from Info's folder row lies over the viewer; Back returns to the photo (the owner, over SPEC-albums §3.3's "with the sheet open": the row closes the sheet)
    // In and out with the shared axis X of the app's pages (owner's test of S4b1: it came and went without a transition)
    // The page last shown, kept for its way out (not state: written while composing)
    val shownOver = remember { arrayOfNulls<Screen>(1) }
    overViewer?.let { shownOver[0] = it }
    AnimatedVisibility(
        visible = viewer != null && overViewer != null,
        enter = slideInHorizontally(spatial) { shift } + fadeIn(effects),
        exit = slideOutHorizontally(spatial) { shift } + fadeOut(fastEffects),
    ) {
        val screen = shownOver[0] ?: return@AnimatedVisibility
        // Tiles of its own: the viewer under it hides the tile of the photo it shows (for the flight back), which here
        // left a gap in the album (owner's test of S4b2)
        val pageOrigins = remember { MediaOrigins() }
        CompositionLocalProvider(LocalMediaOrigins provides pageOrigins) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            BackHandler(enabled = overViewer != null) { overViewer = null }
            val page = when (screen) {
                is Screen.Collection -> PageKey.Collection(CollectionKind.of(screen.kind))
                is Screen.Folder -> FolderKey.of(screen.key)?.let { PageKey.Folder(it) }
                is Screen.Album -> PageKey.User(screen.id)
                else -> null
            }
            if (page != null) {
                AlbumPage(page, onBack = { overViewer = null }, onOpen = { request ->
                    overViewer = null
                    viewer = request
                })
            }
            // Info's small map opens the map over the viewer; Back returns to the photo (as the folder's album)
            if (screen is Screen.Map) {
                MapScreen(onBack = { overViewer = null }, onOpen = { request ->
                    overViewer = null
                    viewer = request
                }, lat = screen.lat, lon = screen.lon)
            }
        }
        }
    }
    // In and out with the shared axis X, as the page over the viewer (owner's test of S7: it came and went at once);
    // the item kept for its way out
    val shownEditing = remember { arrayOfNulls<MediaItem>(1) }
    editing?.let { shownEditing[0] = it }
    AnimatedVisibility(
        visible = editing != null,
        enter = slideInHorizontally(spatial) { shift } + fadeIn(effects),
        exit = slideOutHorizontally(spatial) { shift } + fadeOut(fastEffects),
    ) {
        val item = shownEditing[0] ?: return@AnimatedVisibility
        key(item.id) {
            EditorScreen(item, onClose = { editing = null }, onCopySaved = { copy ->
                // The viewer goes on to the copy, right after the photo it was made from
                val open = viewer
                val ids = open?.ids.orEmpty().toMutableList()
                val at = ids.indexOf(item.id)
                if (at >= 0) ids.add(at + 1, copy) else ids.add(copy)
                editing = null
                viewer = ViewerRequest(ids, copy, album = open?.album, title = open?.title, onPage = open?.onPage ?: {})
            })
        }
    }
    // The slideshow fades in and out over whatever started it; the request kept for its way out
    val shownSlideshow = remember { arrayOfNulls<SlideshowRequest>(1) }
    slideshow?.let { shownSlideshow[0] = it }
    AnimatedVisibility(visible = slideshow != null, enter = fadeIn(effects), exit = fadeOut(fastEffects)) {
        val request = shownSlideshow[0] ?: return@AnimatedVisibility
        key(request) {
            // The end told once the show is gone (composed first, so disposed last): the show puts the window's colour
            // mode back as it leaves, and the viewer going on to the last slide must set its own after that (review
            // before v0.1.0: an HDR photo the show ended on stayed in the default mode)
            val ended = remember { arrayOfNulls<Long>(1) }
            DisposableEffect(Unit) { onDispose { ended[0]?.let(request.onEnd) } }
            SlideshowScreen(request) { last ->
                ended[0] = last
                slideshow = null
            }
        }
    }
    // Move to vault's question (SPEC §18.5): it can't be undone – the items leave the gallery and every other app
    vaultActions?.asking?.let { (items, _) ->
        ConfirmDestructiveDialog(
            R.drawable.ic_lock,
            if (items.size == 1) stringResource(R.string.title_move_to_vault_one) else stringResource(R.string.title_move_to_vault, items.size),
            AnnotatedString(stringResource(R.string.desc_move_to_vault)),
            stringResource(R.string.button_move),
            onConfirm = vaultActions::confirm,
            onDismiss = vaultActions::dismiss,
        )
    }
    vaultActions?.importing?.let { uris -> ImportDialog(uris.size, onConfirm = vaultActions::confirmImport, onDismiss = vaultActions::dismissImport) }
    vaultActions?.exporting?.let { entries -> ExportDialog(entries.size, onConfirm = vaultActions::confirmExport, onDismiss = vaultActions::dismissExport) }
    // Out of the vault (SPEC-albums §18.3): the Add to album sheet with No album first; put back in their albums already
    vaultActions?.returned?.let { back ->
        AddToAlbumSheet(back.items, onDismiss = { vaultActions.returnedDone(noAlbum = false) }, onAdded = { vaultActions.returnedDone(noAlbum = false) }, onNoAlbum = { vaultActions.returnedDone(noAlbum = true) })
    }
    // The page over the viewer goes with it, whichever way it closed (review before v0.1.0: it came back over the next one)
    LaunchedEffect(viewer == null) { if (viewer == null) overViewer = null }
    // Over a page or the editor that covers the viewer, its snackbars show here (review before v0.1.0: Undo of Move to
    // Trash from an album over the viewer, and the editor's failures, were under them)
    if (viewer != null && (overViewer != null || editing != null)) {
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp)) { data ->
            Snackbar(data, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
    errorShown?.let { ErrorDetailsDialog(it) { errorShown = null } }
    if (offering) {
        val answered = {
            offering = false
            messages.launch { app.preferences.endMediaManagementOffers() }
            Unit
        }
        MediaManagementOfferSheet(onNotNow = answered, onOpenSettings = {
            answered()
            MediaManagement.openSettings(appContext)
        })
    }
    if (wizard) {
        WizardScreen(snackbars, messages, onDone = {
            MediaAccess.markWizardDone(appContext)
            wizard = false
        })
        // The restore's messages show over the wizard, above the system's navigation area
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { data ->
            Snackbar(data, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
    }
    }
}
