@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.gallery.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import sh.hnet.gallery.R
import sh.hnet.gallery.model.SourceState
import sh.hnet.gallery.ui.components.AlbumCover
import sh.hnet.gallery.ui.components.LocalMediaActions
import sh.hnet.gallery.ui.components.SettingsGroupHeader
import sh.hnet.gallery.ui.components.SettingsSwitchRow
import sh.hnet.gallery.ui.components.TooltipIconButton
import sh.hnet.gallery.ui.components.albumName
import sh.hnet.gallery.ui.components.albumPath
import sh.hnet.gallery.ui.components.groupShapes
import sh.hnet.gallery.ui.components.settingsRowColors

/** How many folders a group shows before Show all (SPEC-albums §20.3). */
private const val FIRST = 3

/** A state's icon (SPEC-albums §9.10, §20.3). */
@DrawableRes
internal fun SourceState.icon(): Int = when (this) {
    SourceState.IN_PHOTOS -> R.drawable.ic_photo_library
    SourceState.ALBUM_ONLY -> R.drawable.ic_photo_album
    SourceState.HIDDEN -> R.drawable.ic_visibility_off
}

/**
 * Settings › Photos as the sources (SPEC-albums §20.3): "New folders in Photos", then every folder in its
 * state's group – In Photos, Album only, Hidden – the most items first, three of each before Show all;
 * Hidden collapsed into one row. A row's tap opens the state menu; the row then moves to its group and
 * a snackbar names the change with Undo. Search filters every group by name and path (not stored).
 */
@Composable
fun PhotosSettingsScreen(onBack: () -> Unit, viewModel: SourcesViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalMediaActions.current
    val scope = rememberCoroutineScope()
    var searching by rememberSaveable { mutableStateOf(false) }
    val field = rememberTextFieldState()
    var query by remember { mutableStateOf("") }
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect { query = it } }
    val expanded = remember { mutableStateOf(emptySet<SourceState>()) }

    // Names as shown, for searching and the messages
    val screenshotsName = stringResource(R.string.label_screenshots)
    val names = state.entries.associateWith { entry -> if (entry.screenshots) screenshotsName else albumNameText(entry) }
    val paths = state.entries.associateWith { entry -> if (entry.screenshots) "" else albumPathText(entry) }

    val messages = mapOf(
        SourceState.IN_PHOTOS to R.string.msg_source_in_photos,
        SourceState.ALBUM_ONLY to R.string.msg_source_album_only,
        SourceState.HIDDEN to R.string.msg_folder_hidden,
    ).mapValues { (_, id) -> stringResource(id) }

    Column(Modifier.fillMaxSize()) {
        if (searching) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            SearchBarDefaults.InputField(
                textFieldState = field,
                searchBarState = rememberSearchBarState(),
                onSearch = {},
                placeholder = { Text(stringResource(R.string.hint_search_folders), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = {
                    TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), {
                        field.edit { replace(0, length, "") }
                        searching = false
                    })
                },
                trailingIcon = if (query.isNotEmpty()) {
                    { TooltipIconButton(R.drawable.ic_close, stringResource(R.string.content_description_clear_search), { field.edit { replace(0, length, "") } }) }
                } else null,
                colors = SearchBarDefaults.inputFieldColors(
                    focusedContainerColor = SearchBarDefaults.colors().containerColor,
                    unfocusedContainerColor = SearchBarDefaults.colors().containerColor,
                ),
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp).focusRequester(focus),
            )
        } else {
            TopAppBar(
                title = { Text(stringResource(R.string.label_nav_photos), maxLines = 1, modifier = Modifier.semantics { heading() }) },
                navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
                actions = { TooltipIconButton(R.drawable.ic_search, stringResource(R.string.hint_search_folders), { searching = true }) },
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val filter = query.trim()
            if (filter.isEmpty()) {
                SettingsSwitchRow(
                    groupShapes(0, 1), R.drawable.ic_create_new_folder, stringResource(R.string.label_new_folders_in_photos), state.newFoldersInPhotos,
                    onCheckedChange = viewModel::setNewFoldersInPhotos,
                    supporting = stringResource(R.string.desc_new_folders_in_photos),
                )
            }
            for (group in SourceState.entries) {
                val all = state.entries.filter { it.state == group }
                val matching = if (filter.isEmpty()) all else all.filter { names[it].orEmpty().contains(filter, ignoreCase = true) || paths[it].orEmpty().contains(filter, ignoreCase = true) }
                if (matching.isEmpty()) continue
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsGroupHeader(
                        stringResource(
                            when (group) {
                                SourceState.IN_PHOTOS -> R.string.header_state_in_photos
                                SourceState.ALBUM_ONLY -> R.string.header_state_album_only
                                SourceState.HIDDEN -> R.string.header_state_hidden
                            },
                            matching.size,
                        ),
                    )
                    val open = filter.isNotEmpty() || group in expanded.value
                    if (group == SourceState.HIDDEN && !open) {
                        // Hidden collapsed into one row (SPEC-albums §20.3), the only place a hidden folder is seen
                        CollapsedHidden(matching.size) { expanded.value += group }
                    } else {
                        val shown = if (open || matching.size <= FIRST + 1) matching else matching.take(FIRST)
                        val more = matching.size - shown.size
                        val rows = shown.size + if (more > 0) 1 else 0
                        shown.forEachIndexed { index, entry ->
                            SourceRow(index, rows, entry, names[entry].orEmpty(), if (entry.screenshots) stringResource(R.string.label_folders_count, entry.folders.size) else paths[entry].orEmpty()) { chosen ->
                                scope.launch {
                                    val before = viewModel.choose(entry, chosen)
                                    actions.say(messages.getValue(chosen).format(names[entry].orEmpty())) { viewModel.undo(before) }
                                }
                            }
                        }
                        if (more > 0) ShowAllRow(rows - 1, rows, matching.size) { expanded.value += group }
                    }
                }
            }
        }
    }
}

@Composable
private fun albumNameText(entry: SourceEntry): String = albumName(entry.folders.first())

@Composable
private fun albumPathText(entry: SourceEntry): String = albumPath(entry.folders.first())

/** A source's row (SPEC-albums §20.3): its mosaic, name, storage and path (scrolling sideways when long), and the state menu. */
@Composable
private fun SourceRow(index: Int, count: Int, entry: SourceEntry, name: String, supporting: String, onChoose: (SourceState) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val chooseLabel = stringResource(R.string.content_description_choose_state)
    Box {
        SegmentedListItem(
            onClick = { open = true },
            shapes = groupShapes(index, count),
            colors = settingsRowColors(),
            leadingContent = { AlbumCover(entry.newest, Modifier.size(40.dp)) },
            trailingContent = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null) },
            supportingContent = { Text(supporting, maxLines = 1, softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState())) },
            modifier = Modifier.semantics { contentDescription = chooseLabel },
        ) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                SourceState.entries.forEachIndexed { i, state ->
                    SelectableDropdownMenuItem(
                        selected = state == entry.state,
                        onClick = {
                            open = false
                            if (state != entry.state) onChoose(state)
                        },
                        text = {
                            Text(
                                stringResource(
                                    when (state) {
                                        SourceState.IN_PHOTOS -> R.string.state_in_photos
                                        SourceState.ALBUM_ONLY -> R.string.state_album_only
                                        SourceState.HIDDEN -> R.string.state_hidden
                                    },
                                ),
                                maxLines = 1,
                            )
                        },
                        shapes = MenuDefaults.itemShape(i, SourceState.entries.size),
                        leadingIcon = { Icon(painterResource(state.icon()), contentDescription = null) },
                        trailingContent = { if (state == entry.state) Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp)) },
                    )
                }
            }
        }
    }
}

/** "Show all (n)" at a group's foot (SPEC-albums §20.3): the group opens in place. */
@Composable
private fun ShowAllRow(index: Int, count: Int, total: Int, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = groupShapes(index, count),
        colors = settingsRowColors(),
        trailingContent = { Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null) },
    ) {
        Text(stringResource(R.string.button_show_all, total), color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

/** Hidden folders as one row (SPEC-albums §20.3): what they mostly are; a tap opens the group. */
@Composable
private fun CollapsedHidden(count: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    SegmentedListItem(
        onClick = onClick,
        shapes = groupShapes(0, 1),
        colors = settingsRowColors(),
        leadingContent = {
            Box(Modifier.size(40.dp).background(scheme.secondaryContainer, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_visibility_off), contentDescription = null, tint = scheme.onSecondaryContainer)
            }
        },
        trailingContent = { Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null) },
        supportingContent = { Text(stringResource(R.string.desc_hidden_folders), maxLines = 2, overflow = TextOverflow.Ellipsis) },
    ) {
        Text(stringResource(R.string.title_hidden_folders, count), maxLines = 1)
    }
}
