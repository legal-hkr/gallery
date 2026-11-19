package sh.hnet.gallery.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import sh.hnet.gallery.model.MediaItem

/** An item's address for the system and other apps: in its own collection (images or videos) on its own volume. */
fun MediaItem.uri(): Uri = ContentUris.withAppendedId(
    if (video) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Images.Media.getContentUri(volume),
    id,
)
