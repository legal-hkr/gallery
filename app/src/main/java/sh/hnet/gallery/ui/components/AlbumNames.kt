package sh.hnet.gallery.ui.components

import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import sh.hnet.gallery.R
import sh.hnet.gallery.model.FolderKey

/** An album's name (SPEC §9.3): the folder's own, but `DCIM/Camera` is "Camera" and `Download` "Downloads". */
@Composable
fun albumName(folder: FolderKey): String = when {
    folder.path.equals("DCIM/Camera/", ignoreCase = true) -> stringResource(R.string.label_camera)
    folder.path.equals("Download/", ignoreCase = true) -> stringResource(R.string.label_downloads)
    else -> folder.name
}

/**
 * Where an album is (SPEC §9.4): the storage and the path from its root. Any volume but the phone's
 * own counts as an SD card until S4 tells SD cards and USB drives apart.
 */
@Composable
fun albumPath(folder: FolderKey): String {
    val storage = stringResource(if (folder.volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) R.string.label_internal_storage else R.string.label_sd_card)
    return stringResource(R.string.format_album_path, storage, folder.path.trimEnd('/'))
}
