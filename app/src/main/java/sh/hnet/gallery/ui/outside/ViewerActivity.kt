package sh.hnet.gallery.ui.outside

import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.MainActivity
import sh.hnet.gallery.R
import sh.hnet.gallery.data.MediaWriter
import sh.hnet.gallery.data.SourceStore
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.SourceState
import sh.hnet.gallery.ui.components.ErrorDetails
import sh.hnet.gallery.ui.components.ErrorDetailsDialog
import sh.hnet.gallery.ui.components.LocalConsent
import sh.hnet.gallery.ui.components.LocalMediaActions
import sh.hnet.gallery.ui.components.MediaActions
import sh.hnet.gallery.ui.components.rememberConsent
import sh.hnet.gallery.ui.theme.GalleryTheme
import sh.hnet.gallery.ui.viewer.LocalMediaOrigins
import sh.hnet.gallery.ui.viewer.MediaOrigins
import sh.hnet.gallery.ui.viewer.ViewerScreen

/**
 * Ways in from other apps (SPEC §17.1–§17.3, LOGIKA §16.4–§16.5): a file opened from Files or another gallery, an
 * attachment, the camera's last shot. A task of its own, so Back returns to the app that opened it; the same viewer as
 * inside the app, without a flight (there is no tile), turning with the phone as it does.
 */
class ViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The camera on a locked phone (SPEC §17.3): over the lock screen, its own shots only
        if (intent?.action == ACTION_REVIEW_SECURE) setShowWhenLocked(true)
        val opened = intent
        setContent {
            GalleryTheme {
                OutsideViewer(opened, secure = opened?.action == ACTION_REVIEW_SECURE, onClose = ::finish, unlock = ::unlock)
            }
        }
    }

    /** Runs [then] once the phone is unlocked (SPEC §17.3: Share, Edit and More on a locked phone). */
    private fun unlock(then: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            then()
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = then()
        })
    }

    companion object {
        const val ACTION_REVIEW = "android.provider.action.REVIEW"
        const val ACTION_REVIEW_SECURE = "android.provider.action.REVIEW_SECURE"
    }
}

/** What the viewer opens on: the pages' ids, the first one, and the items read for them. */
private class Opening(val ids: List<Long>, val start: Long, val items: List<MediaItem>)

@Composable
private fun OutsideViewer(intent: Intent?, secure: Boolean, onClose: () -> Unit, unlock: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as GalleryApplication
    val resources = LocalResources.current
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val consent = rememberConsent()
    val actions = remember(consent) { MediaActions(context.applicationContext, MediaWriter(context.applicationContext), consent, snackbars, scope) }
    var errorShown by remember { mutableStateOf<ErrorDetails?>(null) }
    SideEffect {
        actions.start = { context.startActivity(it) }
        actions.showError = { errorShown = it }
    }
    var opening by remember { mutableStateOf<Opening?>(null) }
    LaunchedEffect(intent) {
        val found = withContext(Dispatchers.IO) { open(app, intent, secure) }
        if (found == null) onClose() else opening = found
    }

    // Save to phone (SPEC-albums §17.2): a copy in Downloads, and the viewer goes on with it as a normal item there
    fun saveToPhone(item: MediaItem) {
        scope.launch {
            val id = MediaWriter(context).saveToDownloads(item)
            if (id == null) {
                actions.say(resources.getString(R.string.error_save_failed, item.name), undo = null)
                return@launch
            }
            val saved = withContext(Dispatchers.IO) { app.media.item(id) }
            if (saved != null) opening = Opening(listOf(saved.id), saved.id, listOf(saved))
            actions.say(resources.getString(R.string.msg_saved_to_downloads), resources.getString(R.string.button_show)) {
                saved?.let { context.startActivity(MainActivity.openFolder(context, it.folder.key)) }
            }
        }
    }

    CompositionLocalProvider(LocalConsent provides consent, LocalMediaActions provides actions, LocalMediaOrigins provides remember { MediaOrigins() }) {
        // A surface, so text without its own colour gets the theme's (as the picker: black in the dark theme otherwise)
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            opening?.let { now ->
                key(now) {
                    ViewerScreen(
                        now.ids, now.start, snackbars, scope,
                        extra = now.items,
                        locked = secure,
                        unlock = unlock,
                        onSaveToPhone = ::saveToPhone,
                        onPage = {},
                        onLoaded = { _, _ -> },
                        onClose = onClose,
                        // No tile to fly back into: the viewer fades and the activity goes
                        onRequestClose = { _, _ -> false },
                        onOpenFolder = { item -> context.startActivity(MainActivity.openFolder(context, item.folder.key)) },
                    )
                }
            }
        }
    }
    errorShown?.let { ErrorDetailsDialog(it) { errorShown = null } }
}

/**
 * What another app opened (LOGIKA §16.4–§16.5): a MediaStore item with its folder (newest first) – alone when its
 * folder is hidden –, the camera's shots of this session on a locked phone, or a file from outside alone. Null when
 * there is nothing to show.
 */
private fun open(app: GalleryApplication, opened: Intent?, secure: Boolean): Opening? {
    val intent = opened ?: return null
    val uri: Uri = intent.data ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: return null
    val item = app.media.resolve(uri)
    if (item == null) {
        // An attachment, another app's file: alone (SPEC §17.2); the camera's shot must be in MediaStore
        if (intent.action != Intent.ACTION_VIEW) return null
        val outside = app.media.outside(uri, intent.type)
        return Opening(listOf(outside.id), outside.id, listOf(outside))
    }
    if (secure) {
        // A locked phone: only what the camera passed – its shots of this session (SPEC §17.3)
        val passed = buildList {
            add(item)
            val clip = intent.clipData
            if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { app.media.resolve(it) }?.let(::add)
        }.distinctBy { it.id }.filter { !it.trashed }.sortedWith(MediaItem.NEWEST_FIRST)
        return Opening(passed.map { it.id }, item.id, passed)
    }
    val rows = app.sources.rows.value.orEmpty()
    val hidden = SourceStore.stateOf(item.folder, rows, false) == SourceState.HIDDEN
    if (hidden || item.trashed) return Opening(listOf(item.id), item.id, listOf(item))
    // Its folder, newest first (SPEC §17.1); the camera's shots for the camera (SPEC §17.3)
    val folder = app.media.items(volume = item.volume, path = item.path).filter { !it.trashed }.sortedWith(MediaItem.NEWEST_FIRST)
    val list = if (folder.any { it.id == item.id }) folder else listOf(item) + folder
    return Opening(list.map { it.id }, item.id, list)
}
