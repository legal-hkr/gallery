@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.gallery.ui.albums

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.Collator
import java.text.NumberFormat
import kotlinx.coroutines.launch
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.model.AlbumNames
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.ui.components.AlbumCover
import sh.hnet.gallery.ui.components.DialogConfirmButton
import sh.hnet.gallery.ui.components.DialogDismissButton
import sh.hnet.gallery.ui.components.LocalMediaActions
import sh.hnet.gallery.ui.components.LocalOpenAlbum
import sh.hnet.gallery.ui.components.RowIcon
import sh.hnet.gallery.ui.components.groupShapes
import sh.hnet.gallery.ui.components.settingsRowColors

/**
 * Add to album (SPEC-albums §10.1): New album, then the user albums in the Albums tab's order (sources'
 * albums take no items). An album holding all of [items] stays, marked and not tappable; one holding some
 * says how many. A tap adds what isn't there, closes the sheet and says so with Show; nothing on the phone
 * changes. [onAdded] ends the selection.
 */
@Composable
fun AddToAlbumSheet(items: List<MediaItem>, onDismiss: () -> Unit, onAdded: () -> Unit, onNoAlbum: (() -> Unit)? = null) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = context.applicationContext as GalleryApplication
    val actions = LocalMediaActions.current
    val openAlbum = LocalOpenAlbum.current
    val scope = rememberCoroutineScope()
    val albums by app.albums.albums.collectAsStateWithLifecycle()
    val members by app.albums.members.collectAsStateWithLifecycle()
    val library by app.library.collectAsStateWithLifecycle()
    val sort by app.preferences.albumSort.collectAsStateWithLifecycle("RECENT")
    val locale = LocalConfiguration.current.locales[0]
    val number = NumberFormat.getIntegerInstance(locale)
    var naming by rememberSaveable { mutableStateOf(false) }
    val ids = remember(items) { items.mapTo(HashSet()) { it.id } }
    val show = stringResource(R.string.button_show)
    val state = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))

    val rows = remember(albums, members, library, sort, locale) {
        val visible = library?.visible.orEmpty().filter { !it.trashed }.associateBy { it.id }
        val byAlbum = members.orEmpty().groupBy { it.albumId }
        userAlbums(albums.orEmpty(), byAlbum, visible, sort, Collator.getInstance(locale))
    }

    fun added(albumId: Long, name: String, count: Int, skipped: Int) {
        onAdded()
        val message = if (skipped > 0) resources.getString(R.string.msg_added_to_album_skipped, name, count, skipped) else resources.getString(R.string.msg_added_to_album, name, count)
        actions.say(message, show) { openAlbum(albumId) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.title_add_to_album), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
            Text(
                if (items.size == 1) items.first().name else stringResource(R.string.title_selected, items.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
            )
            // Out of the vault (SPEC-albums §18.3): No album first – the items stay in none, not even those they were put back in
            val first = if (onNoAlbum != null) 1 else 0
            val count = rows.size + 1 + first
            if (onNoAlbum != null) {
                SegmentedListItem(
                    onClick = onNoAlbum,
                    shapes = groupShapes(0, count),
                    colors = settingsRowColors(),
                    leadingContent = { RowIcon(R.drawable.ic_close) },
                ) { Text(stringResource(R.string.label_no_album), maxLines = 1) }
            }
            SegmentedListItem(
                onClick = { naming = true },
                shapes = groupShapes(first, count),
                colors = settingsRowColors(),
                leadingContent = { RowIcon(R.drawable.ic_add) },
            ) { Text(stringResource(R.string.label_new_album), maxLines = 1) }
            rows.forEachIndexed { index, album ->
                val inIt = album.memberIds.count { it in ids }
                val all = inIt == ids.size
                SegmentedListItem(
                    onClick = {
                        scope.launch {
                            val (added, skipped) = app.albums.add(album.id, items, System.currentTimeMillis())
                            added(album.id, album.name, added, skipped)
                        }
                    },
                    enabled = !all,
                    shapes = groupShapes(index + 1 + first, count),
                    colors = settingsRowColors(),
                    // 40 dp as the round icons above it and the Frame's album rows (as Music's playlists beside its New playlist)
                    leadingContent = { AlbumCover(album.newest, Modifier.size(40.dp)) },
                    trailingContent = if (all) ({ Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary) }) else null,
                    supportingContent = {
                        Text(
                            when {
                                all -> stringResource(R.string.desc_album_has_all)
                                inIt > 0 -> stringResource(R.string.desc_album_has_some, album.count, inIt)
                                else -> number.format(album.count)
                            },
                            maxLines = 1,
                        )
                    },
                ) { Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (all) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface) }
            }
        }
    }
    if (naming) {
        val taken = albumNamesTaken()
        AlbumNameDialog(stringResource(R.string.title_new_album), "", stringResource(R.string.button_create), taken, onDismiss = { naming = false }) { name ->
            naming = false
            scope.launch {
                val id = app.albums.create(name, items, System.currentTimeMillis())
                added(id, name.trim(), items.size, 0)
            }
        }
    }
}

/**
 * The family's name dialog for an album (SPEC-albums §9.6): the name only – any character, the album isn't a
 * folder –, focused with the keyboard open; Create (or Rename) disabled while empty; a name any album already
 * has (either kind, case ignored) is the field's error.
 */
@Composable
fun AlbumNameDialog(title: String, initial: String, confirmLabel: String, taken: Collection<String>, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    var problem by remember { mutableStateOf<AlbumNames.Problem?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Renaming to the same name is no clash with itself
    val others = remember(taken, initial) { taken.filterNot { it.equals(initial, ignoreCase = true) } }
    val submit = {
        val found = AlbumNames.problem(field.text, others)
        problem = found
        if (found == null) onConfirm(field.text.trim())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = {
                    field = it
                    problem = null
                },
                label = { Text(stringResource(R.string.label_name)) },
                singleLine = true,
                isError = problem != null,
                supportingText = problem?.let { found ->
                    { Text(stringResource(if (found == AlbumNames.Problem.Exists) R.string.error_album_exists else R.string.error_name_empty)) }
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        dismissButton = { DialogDismissButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
        confirmButton = { DialogConfirmButton(onClick = submit, enabled = field.text.isNotBlank()) { Text(confirmLabel) } },
    )
}
