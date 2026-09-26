package app.astiko.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.astiko.R

/**
 * Consistent empty/error state: icon in a tinted circle, title, body,
 * optional action. [error] switches the tint to the error palette.
 * [compact] trims the spacing for in-list hints (an empty-state row
 * inside a scrolling list). [center] vertically centers the block for
 * full-screen states (callers pass a fillMaxSize modifier).
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    error: Boolean = false,
    compact: Boolean = false,
    center: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val pad = if (compact) 12.dp else 24.dp
    Column(
        modifier.fillMaxWidth().padding(pad),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (center) Arrangement.Center else Arrangement.Top,
    ) {
        Box(
            Modifier
                .size(if (compact) 48.dp else 64.dp)
                .clip(CircleShape)
                .background(
                    if (error) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(if (compact) 22.dp else 28.dp),
                tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Trailing affordance for tappable rows (stop rows, line rows, directions). */
@Composable
fun RowChevron(modifier: Modifier = Modifier) {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        modifier = modifier,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Shared chrome of the map control buttons: 40.dp surface, 10.dp corners,
 * drop shadow, click ripple clipped to the shape (M3 standard, the ripple
 * follows the component's shape, not the raw bounds) and a semantics
 * description. All glyphs are Canvas-drawn with the same 2.dp stroke (see
 * [MapCenterButton], [MapZoomInButton], [MapZoomOutButton]), so the whole
 * cluster renders identically. Text glyphs are thinner and unreliable
 * across fonts.
 */
@Composable
private fun MapControlButton(
    onClick: () -> Unit,
    modifier: Modifier,
    description: String,
    glyph: @Composable () -> Unit,
) {
    Surface(
        modifier =
            modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .semantics { contentDescription = description },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) { glyph() }
    }
}

/**
 * Map control button with a "crosshair + dot" center glyph: a circle
 * with four ticks separated from it by a small gap and a filled dot in
 * the middle.
 */
@Composable
fun MapCenterButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Resolved before the modifier chain: the semantics block is not a
    // composable scope, so stringResource cannot be called inside it.
    val description = stringResource(R.string.center_map)
    MapControlButton(onClick = onClick, modifier = modifier, description = description) {
        val color = MaterialTheme.colorScheme.onSurface
        Canvas(Modifier.size(20.dp)) {
            val stroke = 2.dp.toPx()
            drawCircle(
                color = color,
                radius = size.minDimension * 0.30f,
                style = Stroke(width = stroke),
            )
            // Four ticks outside the circle with a gap (dist 0.38 -> 0.45
            // of the size from the center). Round caps reach the edge.
            val tickIn = 0.12f * size.minDimension
            val tickOut = 0.05f * size.minDimension
            drawLine(
                color = color,
                start = Offset(center.x, tickIn),
                end = Offset(center.x, tickOut),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(center.x, size.height - tickIn),
                end = Offset(center.x, size.height - tickOut),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(tickIn, center.y),
                end = Offset(tickOut, center.y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(size.width - tickIn, center.y),
                end = Offset(size.width - tickOut, center.y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            // Filled center dot (r 0.09 of the size).
            drawCircle(
                color = color,
                radius = size.minDimension * 0.09f,
                style = Fill,
            )
        }
    }
}

/**
 * Map control button with a "+" zoom-in glyph, Canvas-drawn with the
 * same 2.dp stroke as [MapCenterButton]'s crosshair but sized to the
 * old titleLarge (22sp Manrope) "+" glyph.
 */
@Composable
fun MapZoomInButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.zoom_in)
    MapControlButton(onClick = onClick, modifier = modifier, description = description) {
        MapZoomGlyph(zoomIn = true)
    }
}

/**
 * Map control button with a "−" zoom-out glyph, Canvas-drawn with the
 * same 2.dp stroke as [MapCenterButton]'s crosshair but sized to the
 * old titleLarge (22sp Manrope) "−" glyph.
 */
@Composable
fun MapZoomOutButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.zoom_out)
    MapControlButton(onClick = onClick, modifier = modifier, description = description) {
        MapZoomGlyph(zoomIn = false)
    }
}

/**
 * +/− glyph on the same 20.dp canvas and 2.dp stroke as the crosshair.
 * The arms match the titleLarge (22sp Manrope) glyphs: "+" 0.46em
 * wide and "−" 0.42em wide, only thicker (2.dp). Round caps overhang by
 * stroke/2 per end, so the line spans shrink accordingly to keep the
 * visible ink at those sizes.
 */
@Composable
private fun MapZoomGlyph(zoomIn: Boolean) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(20.dp)) {
        val stroke = 2.dp.toPx()
        // Gap per side = (canvas − ink + stroke) / 2: plus 0.30 -> 10.dp
        // of ink, minus 0.32 -> 9.2.dp of ink.
        val gap = size.minDimension * if (zoomIn) 0.30f else 0.32f
        if (zoomIn) {
            // Vertical arm of the "+".
            drawLine(
                color = color,
                start = Offset(center.x, gap),
                end = Offset(center.x, size.height - gap),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        // Horizontal arm: the "−" is this alone.
        drawLine(
            color = color,
            start = Offset(gap, center.y),
            end = Offset(size.width - gap, center.y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * Overlay card at the bottom of a map. Default: content left, ✕ right,
 * one row. With [header] the header becomes a title row carrying the ✕
 * and the content moves below it, spanning the card width, so it can
 * align under the ✕ (the arrivals bus card uses this).
 */
@Composable
fun MapInfoCard(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    // Optional action shown between the content and the close button
    // (e.g. The arrivals shortcut on the line map's stop card).
    action: (@Composable () -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        if (header == null) {
            Row(
                Modifier.padding(start = 16.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) { content() }
                if (action != null) {
                    action()
                }
                // 34.dp visual (touch target stays 48.dp, M3 IconButton's
                // minimumInteractiveComponentSize). The single-row card's
                // ✕ must not be the tallest thing on it. A 40.dp + 20.dp
                // padding row would be taller than the badge/pill it closes.
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Column(
                Modifier.padding(start = 16.dp, top = 2.dp, end = 4.dp, bottom = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { header() }
                    if (action != null) {
                        action()
                    }
                    // 40.dp (not the default 48) so the header row
                    // isn't taller than its text. Same state-layer size
                    // as the top-bar FavoriteIcon.
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 8.dp on top of the Column's 4.dp: the content's right
                // edge lands under the ✕ glyph.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp),
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * Decouples a list nested inside a scrollable page: when the list reaches
 * an edge and the user keeps scrolling, the leftover deltas are consumed
 * here so the outer page never moves (the default nested-scroll behavior
 * hands them to the page, making the whole screen drift). The page still
 * scrolls normally when the gesture starts outside the list.
 */
fun Modifier.consumeEdgeOverscroll(): Modifier =
    nestedScroll(
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset = Offset.Zero

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset = available

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity = available
        },
    )

/**
 * Route number badge (e.g. "040", "87N").
 *
 * Min-width + centered text so 1-, 2- and 3-char codes all render at the
 * same width, so the name text that follows (12.dp spacer) stays aligned
 * across list rows regardless of code length. Longer codes still grow
 * the badge instead of clipping.
 *
 * No fillMaxWidth() here: the incoming max width is the row's, so the
 * badge would stretch to the whole row. The widthIn min constraint on
 * the Surface already forces the 48.dp box.
 */
@Composable
fun LineBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(6.dp),
        modifier = modifier.widthIn(min = 48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** Small neutral badge (stop order numbers, "+n" overflow, …).
 *
 * Min-width + centered text so 1- and 2-digit order numbers render at the
 * same width, so the stop name that follows stays aligned across list
 * rows (same trick as [LineBadge]). Longer text still grows instead of
 * clipping. */
@Composable
fun SmallBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    Surface(
        color = color ?: MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier.widthIn(min = 28.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                // Colored chips (e.g. The route's green/charcoal terminus
                // badges) need white text. The neutral chip keeps the
                // theme's default.
                color = if (color != null) Color.White else Color.Unspecified,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Favorite heart that pops (scale pulse) when the state flips. The
 * ripple is clipped to a circle matching the box size: callers that
 * want the standard icon-button look pass a fixed size (e.g.
 * `minimumInteractiveComponentSize().size(40.dp)` in a top bar, same
 * 40.dp state layer as IconButton). Left unwrapped (list rows) the
 * ripple hugs the 24.dp icon.
 *
 * [onLongClick] hangs a second action on the same box (hold the heart to
 * act on the stop). A parent's tap detector would never see it, the box
 * consumes the gesture itself.
 */
@Composable
fun FavoriteIcon(
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    val pop = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(isFavorite) {
        // Skip the pulse on first composition (rows are often already
        // favorited when they appear).
        if (first) {
            first = false
            return@LaunchedEffect
        }
        pop.snapTo(0.6f)
        pop.animateTo(1.2f, tween(140))
        pop.animateTo(1f, tween(120))
    }
    Box(
        modifier =
            modifier
                .clip(CircleShape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                    onLongClickLabel = onLongClickLabel,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription =
                stringResource(
                    if (isFavorite) R.string.remove_favorite else R.string.add_favorite,
                ),
            modifier =
                Modifier.size(24.dp).graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                },
            tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Rounded clip applied to every list row (via [TwoLineRow] and the
 * bespoke rows in the screens) so ripples, and the arrivals ETA flash,
 * follow a uniform rounded shape across all lists. 10.dp matches the map
 * control buttons.
 */
internal val ListRowShape = RoundedCornerShape(10.dp)

/**
 * A two-line list row whose trailing action sits ON the headline line
 * (centered against it). ListItem/IconButton center against the whole
 * row, which makes the icon look "too low" next to the stop name.
 */
@Composable
fun TwoLineRow(
    onClick: () -> Unit,
    headline: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supporting: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(ListRowShape)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        TwoLineRowContent(headline, trailing, supporting)
    }
}

/** Same row without the clickable ripple: display-only rows (settings
 *  cache stats, last sync) must not look tappable. */
@Composable
fun PlainTwoLineRow(
    headline: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supporting: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(ListRowShape)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        TwoLineRowContent(headline, trailing, supporting)
    }
}

/**
 * Filled tonal search field, the M3 search pattern: 28.dp pill, no
 * indicator, a clear button while there is a query. The line search, the
 * stop search and the city picker share it so the three stay identical.
 * Callers pass the horizontal padding (and a focus requester) through
 * [modifier].
 */
@Composable
fun SearchPill(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.clear),
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
    )
}

/** Section title row with optional trailing actions (a refresh button). */
@Composable
fun SectionHeader(
    title: String,
    actions: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        actions()
    }
}

/**
 * Line row: number badge, name, chevron. The arrivals screen's "lines at
 * this stop" row and the timetable's line header keep their own layouts
 * (a second destination line, a different type scale).
 */
@Composable
fun LineListRow(
    shortName: String,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineBadge(shortName)
                Text(
                    title,
                    Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        modifier = modifier.clip(ListRowShape).clickable(onClick = onClick),
        trailingContent = { RowChevron() },
    )
}

/**
 * Row of a settings or info card: title, optional subtitle, optional
 * trailing icon. [icon] is the external-link glyph for links, the
 * chevron for drill-ins, null for none.
 */
@Composable
fun SettingsRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconDescription: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(ListRowShape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (icon != null) {
            Icon(
                icon,
                contentDescription = iconDescription,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RowScope.TwoLineRowContent(
    headline: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    supporting: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { headline() }
            // The icon sits on the headline line (centered against it).
            // No fixed-size box, which would inflate single-line rows.
            trailing()
        }
        supporting?.let {
            Spacer(Modifier.height(2.dp))
            it()
        }
    }
}
