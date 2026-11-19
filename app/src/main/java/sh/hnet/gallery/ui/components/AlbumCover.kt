package sh.hnet.gallery.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.Mosaic

/**
 * An album's cover (SPEC §9.3): its four newest items in a 2 × 2 mosaic, [gap] apart, inside one clip of
 * [corner] – fewer as [Mosaic] lays them out. Used as a row's 40–48 dp cover and as the Albums tab's tile.
 */
@Composable
fun AlbumCover(items: List<MediaItem>, modifier: Modifier = Modifier, corner: Dp = 12.dp, gap: Dp = 1.dp) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        val shown = items.take(4)
        val cells = Mosaic.cells(shown.size)
        val side = maxWidth
        val px = with(LocalDensity.current) { side.roundToPx() }
        shown.forEachIndexed { index, item ->
            val cell = cells[index]
            // Half a gap on each inner side of a cell
            val left = side * cell.left + if (cell.left > 0f) gap / 2 else 0.dp
            val top = side * cell.top + if (cell.top > 0f) gap / 2 else 0.dp
            val right = side * cell.right - if (cell.right < 1f) gap / 2 else 0.dp
            val bottom = side * cell.bottom - if (cell.bottom < 1f) gap / 2 else 0.dp
            Box(Modifier.offset(left, top).size(right - left, bottom - top)) {
                MediaThumbnail(item, (px * (cell.right - cell.left)).toInt().coerceAtLeast(1), Modifier.size(right - left, bottom - top))
            }
        }
    }
}
