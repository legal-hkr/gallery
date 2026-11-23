package sh.hnet.gallery.ui.pick

import android.content.ClipData
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.stringResource
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.data.MediaWriter
import sh.hnet.gallery.data.uri
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.PickRequest
import sh.hnet.gallery.ui.components.ErrorDetails
import sh.hnet.gallery.ui.components.ErrorDetailsDialog
import sh.hnet.gallery.ui.components.LocalConsent
import sh.hnet.gallery.ui.components.LocalMediaActions
import sh.hnet.gallery.ui.components.MediaActions
import sh.hnet.gallery.ui.components.rememberConsent
import sh.hnet.gallery.ui.theme.GalleryTheme
import sh.hnet.gallery.ui.viewer.LocalMediaOrigins
import sh.hnet.gallery.ui.viewer.MediaOrigins
import sh.hnet.gallery.ui.viewer.ViewerHost
import sh.hnet.gallery.ui.viewer.ViewerRequest

/**
 * Picking for another app (SPEC §17.4, LOGIKA §16.6): `GET_CONTENT` and `PICK` for photos and videos. Returns the
 * chosen items' MediaStore addresses with a read grant – `data` for one, `ClipData` too for several –, or cancels.
 * The addresses are MediaStore's own, so the receiving app gets the photo without its location unless it has the
 * right to it.
 */
class PickerActivity : ComponentActivity() {

    // Dark mode or the language switched in place (configChanges has uiMode and locale, so nothing on screen is lost –
    // review before v0.1.0; the owner: a change of language closed the editor and reset the map): the system bars'
    // icons follow the mode again
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        (application as GalleryApplication).startLibrary()
        val request = PickRequest.of(intent?.type ?: intent?.resolveType(this), intent?.getStringArrayExtra(Intent.EXTRA_MIME_TYPES), intent?.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) == true)
        setContent {
            GalleryTheme {
                PickerContent(request, onCancel = {
                    setResult(RESULT_CANCELED)
                    finish()
                }) { items -> finishWith(items) }
            }
        }
    }

    private fun finishWith(items: List<MediaItem>) {
        if (items.isEmpty()) {
            setResult(RESULT_CANCELED)
        } else {
            val uris = items.map { it.uri() }
            val result = Intent().setData(uris.first()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val clip = ClipData.newRawUri(null, uris.first())
            for (uri in uris.drop(1)) clip.addItem(ClipData.Item(uri))
            result.clipData = clip
            setResult(RESULT_OK, result)
        }
        finish()
    }
}

@Composable
private fun PickerContent(request: PickRequest, onCancel: () -> Unit, onDone: (List<MediaItem>) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as GalleryApplication
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val consent = rememberConsent()
    val actions = remember(consent) { MediaActions(context.applicationContext, MediaWriter(context.applicationContext), consent, snackbars, scope) }
    var errorShown by remember { mutableStateOf<ErrorDetails?>(null) }
    SideEffect {
        actions.start = { context.startActivity(it) }
        actions.showError = { errorShown = it }
    }
    // A picker opened before the app ever ran reads the media itself
    LaunchedEffect(Unit) { app.index.recheck() }
    var looking by remember { mutableStateOf<ViewerRequest?>(null) }
    val title = stringResource(
        when (request.kind) {
            PickRequest.Kind.PHOTOS -> if (request.multiple) R.string.title_pick_photos else R.string.title_pick_photo
            PickRequest.Kind.VIDEOS -> if (request.multiple) R.string.title_pick_videos else R.string.title_pick_video
            PickRequest.Kind.ITEMS -> if (request.multiple) R.string.title_pick_items else R.string.title_pick_item
        },
    )
    CompositionLocalProvider(LocalConsent provides consent, LocalMediaActions provides actions, LocalMediaOrigins provides remember { MediaOrigins() }) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            PickerScreen(request, title, onClose = onCancel, onDone = onDone, onLook = { looking = it })
            looking?.let { request ->
                key(request) { ViewerHost(request, snackbars, scope, onClose = { looking = null }) }
            }
        }
    }
    errorShown?.let { ErrorDetailsDialog(it) { errorShown = null } }
}
