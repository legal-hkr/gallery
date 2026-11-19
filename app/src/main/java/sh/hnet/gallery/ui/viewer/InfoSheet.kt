@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.gallery.ui.viewer

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TimePickerDialogDefaults
import androidx.compose.material3.TimePickerDisplayMode
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.gallery.GalleryApplication
import sh.hnet.gallery.R
import sh.hnet.gallery.data.originalUri
import sh.hnet.gallery.data.uri
import sh.hnet.gallery.facts.Fact
import sh.hnet.gallery.model.ExifText
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.Playback
import sh.hnet.gallery.model.TimelineFolders
import sh.hnet.gallery.ui.components.RowIcon
import sh.hnet.gallery.ui.components.TooltipIconButton
import sh.hnet.gallery.ui.components.albumName
import sh.hnet.gallery.ui.components.albumPath
import sh.hnet.gallery.ui.components.groupShapes
import sh.hnet.gallery.ui.components.settingsRowColors
import sh.hnet.gallery.ui.grid.formatDate
import sh.hnet.gallery.ui.grid.formatDuration

/** What the file itself says, read when the sheet opens (SPEC §7.1). */
private class FileInfo(
    val camera: String?,
    val settings: String?,
    val lens: String?,
    val latitude: Double?,
    val longitude: Double?,
    val located: Boolean,
    val motionMs: Long?,
    val video: VideoInfo? = null,
)

/** A video's own details (SPEC §7.1's `movie` row), read from the file. */
private class VideoInfo(val durationMs: Long, val width: Int, val height: Int, val frameRate: Float?, val hdr: String?, val channels: Int?)

/** Formats whose date `ExifInterface` can write (SPEC §7.2: no pencil for HEIC, DNG or videos). */
private val DATE_WRITABLE = setOf("image/jpeg", "image/png", "image/webp")

/**
 * The Info sheet (SPEC §7.1): the date with the weekday and time (a pencil changes it, §7.2), the
 * location as coordinates with Open in a maps app (names and the small map come with S6), the
 * camera, the file, the folder, HDR and motion. Absent values leave the row out; wide ones scroll
 * sideways; a long press copies a value. In landscape it is capped at 640 dp and opens fully.
 *
 * @param onChangeDate The new date and time, in the phone's zone
 */
@Composable
fun InfoSheet(
    item: MediaItem,
    fact: Fact?,
    hdr: Boolean,
    landscape: Boolean,
    playback: ViewerPlayer.State?,
    onChangeDate: ((LocalDate, LocalTime) -> Unit)?,
    onDismiss: () -> Unit,
    onOpenFolder: (() -> Unit)? = null,
    albums: List<Pair<Long, String>> = emptyList(),
    onOpenAlbum: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val locale = LocalConfiguration.current.locales[0]
    val app = LocalContext.current.applicationContext as GalleryApplication
    val location = remember { app.media.canLocate() }
    val info by produceState<FileInfo?>(null, item.id, item.generation) { value = readInfo(resolver, item, fact, location) }
    // Partly open in portrait (the first rows), drags to full; fully open in landscape (SPEC §7.1), as Calendar's colour sheet
    val state = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = if (landscape) setOf(SheetValue.Hidden, SheetValue.Expanded) else setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded),
    )
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }

    val zone = fact?.offsetMinutes?.let { ZoneOffset.ofTotalSeconds(it * 60) } ?: ZoneId.systemDefault()
    val taken = ZonedDateTime.ofInstant(Instant.ofEpochMilli(item.taken), zone)

    // The sheet opens with its rows complete: rows added while it rose changed its anchors (half the screen or the content's
    // height) mid-way, so it jumped to a lower place (owner, 19 Nov 2026). The file is read in tens of milliseconds; a slow
    // read doesn't hold the sheet back beyond INFO_WAIT_MS
    var waited by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id) {
        delay(INFO_WAIT_MS)
        waited = true
    }
    if (info == null && !waited) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        sheetMaxWidth = if (landscape) 640.dp else BottomSheetDefaults.SheetMaxWidth,
    ) {
        Column(
            // The sheet keeps clear of the navigation bar itself (as the family's sheets); padding for it here as well made the content shrink once the sheet's window got its insets, so the sheet sank back a little after opening (owner, 19 Nov 2026)
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(stringResource(R.string.title_info), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp).semantics { heading() })
            val rows = buildRows(item, fact, info, hdr, taken, locale, onOpenFolder)
            // The user albums holding the item, last (SPEC-albums §7.1); absent when it is in none
            val count = rows.size + if (albums.isNotEmpty()) 1 else 0
            rows.forEachIndexed { index, row ->
                InfoRow(index, count, row, trailing = when {
                    row.date && onChangeDate != null && !item.video && item.mime in DATE_WRITABLE -> {
                        { TooltipIconButton(R.drawable.ic_edit, stringResource(R.string.content_description_edit_date), { pickingDate = true }) }
                    }
                    row.open != null -> {
                        { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) }
                    }
                    else -> null
                })
            }
            if (albums.isNotEmpty()) AlbumsRow(rows.size, count, albums, onOpenAlbum)
            val lat = info?.latitude
            val lon = info?.longitude
            if (lat != null && lon != null) MapsButton(lat, lon)
        }
    }

    if (pickingDate) {
        // The picker counts in UTC days
        val picker = rememberDatePickerState(initialSelectedDateMillis = taken.toLocalDate().toEpochDay() * DAY_MILLIS)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                    pickingDate = false
                    picker.selectedDateMillis?.let { pickedDate = LocalDate.ofEpochDay(Math.floorDiv(it, DAY_MILLIS)) }
                }) { Text(stringResource(R.string.button_next)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.button_cancel)) } },
        ) {
            DatePicker(picker)
        }
    }
    pickedDate?.let { date ->
        val picker = rememberTimePickerState(initialHour = taken.hour, initialMinute = taken.minute, is24Hour = DateFormat.is24HourFormat(context))
        var input by rememberSaveable { mutableStateOf(false) }
        val mode = if (input) TimePickerDisplayMode.Input else TimePickerDisplayMode.Picker
        TimePickerDialog(
            onDismissRequest = { pickedDate = null },
            confirmButton = {
                TextButton(onClick = {
                    pickedDate = null
                    onChangeDate?.invoke(date, LocalTime.of(picker.hour, picker.minute))
                }) { Text(stringResource(R.string.button_save)) }
            },
            dismissButton = { TextButton(onClick = { pickedDate = null }) { Text(stringResource(R.string.button_cancel)) } },
            title = { TimePickerDialogDefaults.Title(displayMode = mode) },
            modeToggleButton = { TimePickerDialogDefaults.DisplayModeToggle(onDisplayModeChange = { input = !input }, displayMode = mode) },
        ) {
            if (input) TimeInput(picker) else TimePicker(picker)
        }
    }
}

private const val DAY_MILLIS = 86_400_000L

/** Longest wait for the file's details before the sheet opens without them. */
private const val INFO_WAIT_MS = 300L

/** One row of the sheet: its icon, title and supporting lines; [date] marks the row with the pencil. */
/** @param open What a tap opens (the folder row: its album, SPEC-albums §7.1); a long press still copies */
private class Row(@DrawableRes val icon: Int, val title: String, val lines: List<String>, val date: Boolean = false, val open: (() -> Unit)? = null)

@Composable
private fun buildRows(item: MediaItem, fact: Fact?, info: FileInfo?, hdr: Boolean, taken: ZonedDateTime, locale: Locale, onOpenFolder: (() -> Unit)?): List<Row> {
    val context = LocalContext.current
    val rows = ArrayList<Row>()
    val dateText = formatDate(stringResource(R.string.format_day_year), taken.toLocalDate(), locale)
    val clock = DateFormat.getTimeFormat(context).apply { timeZone = TimeZone.getTimeZone(taken.zone) }.format(Date(item.taken))
    // The photo's own zone when it isn't the phone's (owner's test of S2a, 19 Nov 2026: 18:30 in New York read as the phone's time)
    val phoneOffset = ZoneId.systemDefault().rules.getOffset(taken.toInstant())
    val time = if (taken.offset != phoneOffset) stringResource(R.string.format_time_zone, clock, utcOffset(taken.offset.totalSeconds)) else clock
    rows.add(Row(R.drawable.ic_calendar_month, dateText, listOf(time), date = true))

    val lat = info?.latitude
    val lon = info?.longitude
    if (lat != null && lon != null) {
        val coordinates = stringResource(R.string.format_coordinates, ExifText.coordinate(lat, locale), if (lat >= 0) "N" else "S", ExifText.coordinate(lon, locale), if (lon >= 0) "E" else "W")
        rows.add(Row(R.drawable.ic_location_on, coordinates, emptyList()))
    } else if (info != null) {
        val why = when {
            !info.located -> stringResource(R.string.desc_place_unavailable)
            item.video -> stringResource(R.string.desc_no_location_video)
            else -> stringResource(R.string.desc_no_location_photo)
        }
        rows.add(Row(R.drawable.ic_location_off, stringResource(R.string.title_no_location), listOf(why)))
    }

    info?.camera?.let { camera -> rows.add(Row(R.drawable.ic_photo_camera, camera, listOfNotNull(info.settings, info.lens))) }
    // A video's row in place of the camera's (SPEC §7.1): length, size, frame rate; under it HDR and sound
    info?.video?.let { video ->
        val fps = video.frameRate?.let { Playback.fpsText(it, locale) }
        val dims = "${video.width} × ${video.height}"
        val title = if (fps != null) stringResource(R.string.format_video_details, formatDuration(video.durationMs), dims, fps) else "${formatDuration(video.durationMs)}, $dims"
        val sound = when {
            video.channels == null || video.channels == 0 -> stringResource(R.string.label_no_sound)
            video.channels == 1 -> stringResource(R.string.label_sound_mono)
            else -> stringResource(R.string.label_sound_stereo)
        }
        val hdrText = video.hdr?.let { stringResource(R.string.label_hdr_video, it) }
        rows.add(Row(R.drawable.ic_movie, title, listOf(listOfNotNull(hdrText, sound).joinToString(", "))))
    }

    val size = Formatter.formatFileSize(context, item.size)
    val dims = if (item.width > 0 && item.height > 0) "${item.width} × ${item.height}" else null
    val details = if (dims != null) stringResource(R.string.format_file_details, size, dims, ExifText.megapixels(item.width, item.height, locale)) else size
    rows.add(Row(R.drawable.ic_description, item.name, listOf(details)))
    // The source's album (SPEC-albums §7.1): its name, the storage and path; a tap opens it – not for a hidden folder
    if (!item.outside) {
        val folderTitle = if (TimelineFolders.isScreenshots(item.path)) stringResource(R.string.label_screenshots) else albumName(item.folder)
        rows.add(Row(R.drawable.ic_folder, folderTitle, listOf(albumPath(item.folder)), open = onOpenFolder))
    }

    if (!item.video && (hdr || fact?.gainmap == true)) rows.add(Row(R.drawable.ic_hdr_on, stringResource(R.string.title_hdr), listOf(stringResource(R.string.label_ultra_hdr))))
    if (fact?.motion == true) {
        val length = info?.motionMs?.let { ms -> ExifText.seconds(ms / 1000.0, locale) + " s" }
        rows.add(Row(R.drawable.ic_motion_photos_on, stringResource(R.string.title_motion_photo), listOfNotNull(length?.let { stringResource(R.string.label_motion_length, it) })))
    }
    return rows
}

/** An offset as "UTC−05:00", "UTC+09:00", "UTC" (a typographic minus, as the system writes zones). */
private fun utcOffset(seconds: Int): String {
    if (seconds == 0) return "UTC"
    val minutes = Math.abs(seconds) / 60
    return "UTC" + (if (seconds < 0) "−" else "+") + "%02d:%02d".format(Locale.ROOT, minutes / 60, minutes % 60)
}

/** A segmented row with its icon box; the title and each line scroll sideways when wide, and a long press copies the value. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InfoRow(index: Int, count: Int, row: Row, trailing: (@Composable () -> Unit)?) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copy: (String) -> Unit = { text -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text))) } }
    SegmentedListItem(
        shapes = groupShapes(index, count),
        colors = settingsRowColors(),
        onClick = row.open ?: {},
        enabled = true,
        leadingContent = { RowIcon(row.icon) },
        trailingContent = trailing?.let { { it() } },
        supportingContent = if (row.lines.isEmpty()) null else {
            {
                Column {
                    row.lines.forEach { line -> Value(line, Modifier.combinedClickable(onLongClick = { copy(line) }, onClick = row.open ?: {})) }
                }
            }
        },
    ) {
        Value(row.title, Modifier.combinedClickable(onLongClick = { copy(row.title) }, onClick = row.open ?: {}))
    }
}

/** "In albums" (SPEC-albums §7.1): the user albums as chips in one line that scrolls sideways; a chip opens its album. */
@Composable
private fun AlbumsRow(index: Int, count: Int, albums: List<Pair<Long, String>>, onOpen: (Long) -> Unit) {
    SegmentedListItem(
        shapes = groupShapes(index, count),
        colors = settingsRowColors(),
        leadingContent = { RowIcon(R.drawable.ic_photo_album) },
        supportingContent = {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((id, name) in albums) {
                    AssistChip(
                        onClick = { onOpen(id) },
                        label = { Text(name, maxLines = 1) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_photo_album), contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
        },
    ) {
        Text(stringResource(R.string.title_in_albums), maxLines = 1)
    }
}

/** One line that scrolls sideways instead of wrapping (SPEC §2 item 8). */
@Composable
private fun Value(text: String, modifier: Modifier) {
    Text(text, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, modifier = modifier.horizontalScroll(rememberScrollState()))
}

/** Open in a maps app (SPEC §7.3): only when some app answers `geo:` – the app itself has no network. */
@Composable
private fun MapsButton(lat: Double, lon: Double) {
    val context = LocalContext.current
    val intent = remember(lat, lon) { Intent(Intent.ACTION_VIEW, "geo:$lat,$lon?q=$lat,$lon".toUri()) }
    val available = remember(intent) { intent.resolveActivity(context.packageManager) != null }
    if (!available) return
    TextButton(onClick = { runCatching { context.startActivity(intent) } }, modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.button_open_in_maps))
    }
}

/** The file's camera details, location and motion length, read on the IO thread; nothing when unreadable. */
private suspend fun readInfo(resolver: ContentResolver, item: MediaItem, fact: Fact?, location: Boolean): FileInfo = withContext(Dispatchers.IO) {
    if (item.video) return@withContext readVideo(resolver, item, location)
    var camera: String? = null
    var settings: String? = null
    var lens: String? = null
    val locale = Locale.getDefault()
    if (!item.video) {
        try {
            resolver.openFileDescriptor(item.uri(), "r")?.use { descriptor ->
                val exif = ExifInterface(descriptor.fileDescriptor)
                val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                camera = listOfNotNull(make?.takeIf { model == null || !model.startsWith(it, ignoreCase = true) }, model).joinToString(" ").ifEmpty { null }
                settings = listOfNotNull(
                    exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { ExifText.aperture(it, locale) },
                    exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let { ExifText.exposure(it, locale) },
                    exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 }?.let { ExifText.iso(it) },
                    exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { ExifText.focalLength(it, locale) },
                ).joinToString(", ").ifEmpty { null }
                lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.ifEmpty { null }
            }
        } catch (_: Exception) {
        }
    }
    val motionMs = if (fact?.motion == true && fact.motionLength != null) motionDuration(resolver, item, fact.motionLength) else null
    FileInfo(camera, settings, lens, fact?.latitude, fact?.longitude, fact?.located ?: false, motionMs)
}

/** A motion photo's video length, read from the tail of the file without copying it. */
private fun motionDuration(resolver: ContentResolver, item: MediaItem, length: Long): Long? = try {
    resolver.openFileDescriptor(item.uri(), "r")?.use { descriptor ->
        val start = descriptor.statSize - length
        val channel = FileInputStream(descriptor.fileDescriptor).channel
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= length) return -1
                    val count = minOf(size.toLong(), length - position).toInt()
                    return channel.read(ByteBuffer.wrap(buffer, offset, count), start + position)
                }

                override fun getSize(): Long = length

                override fun close() {}
            })
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }
} catch (_: Exception) {
    null
}

/** A date for the viewer's title (SPEC §6.2): "Today", "Yesterday", else the day and month, with the year when it isn't this one. */
@Composable
fun viewerTitle(item: MediaItem, fact: Fact?): String {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val zone = fact?.offsetMinutes?.let { ZoneOffset.ofTotalSeconds(it * 60) } ?: ZoneId.systemDefault()
    val taken = ZonedDateTime.ofInstant(Instant.ofEpochMilli(item.taken), zone)
    val today = LocalDate.now()
    val day = when (taken.toLocalDate()) {
        today -> stringResource(R.string.label_today)
        today.minusDays(1) -> stringResource(R.string.label_yesterday)
        else -> {
            val skeleton = if (taken.year == today.year) "dMMMM" else "dMMMMy"
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(taken)
        }
    }
    val time = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm a", locale).format(taken)
    return stringResource(R.string.format_viewer_title, day, time)
}

/**
 * A video's details and location (SPEC §7.1, LOGIKA §14.6): length, upright size, frame rate, HDR
 * kind and sound from its tracks; the location (`©xyz`, ISO 6709) only with access to it – the
 * system cuts it out otherwise.
 */
private fun readVideo(resolver: ContentResolver, item: MediaItem, location: Boolean): FileInfo {
    var video: VideoInfo? = null
    var latitude: Double? = null
    var longitude: Double? = null
    try {
        val uri = if (location) item.originalUri() else item.uri()
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(descriptor.fileDescriptor)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: item.duration
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: item.width
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: item.height
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                Playback.iso6709(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))?.let { (lat, lon) ->
                    latitude = lat
                    longitude = lon
                }
                var frameRate: Float? = null
                var hdr: String? = null
                var channels: Int? = null
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(descriptor.fileDescriptor)
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                        if (mime.startsWith("video/") && frameRate == null) {
                            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) frameRate = format.getNumber(MediaFormat.KEY_FRAME_RATE)?.toFloat()
                            val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
                            hdr = when {
                                mime == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION -> "Dolby Vision"
                                transfer == MediaFormat.COLOR_TRANSFER_HLG -> "HLG"
                                transfer == MediaFormat.COLOR_TRANSFER_ST2084 -> "HDR10"
                                else -> null
                            }
                        }
                        if (mime.startsWith("audio/") && channels == null) channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                    }
                } finally {
                    extractor.release()
                }
                val upright = rotation % 180 != 0
                video = VideoInfo(duration, if (upright) height else width, if (upright) width else height, frameRate, hdr, channels ?: 0)
            } finally {
                retriever.release()
            }
        }
    } catch (_: Exception) {
    }
    return FileInfo(null, null, null, latitude, longitude, location, null, video)
}

