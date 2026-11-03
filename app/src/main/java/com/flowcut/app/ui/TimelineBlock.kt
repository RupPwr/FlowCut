package com.flowcut.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowcut.app.model.DP_PER_SECOND
import com.flowcut.app.model.MAX_CLIPS
import com.flowcut.app.model.ProgramTimeline
import com.flowcut.app.model.TransitionType
import kotlin.math.abs
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TimelineBlock(
    timeline: ProgramTimeline,
    currentMs: Long,
    selectedClipIndex: Int?,
    selectedJunctionIndex: Int?,
    onSelectClip: (Int) -> Unit,
    onDeleteClip: (Int) -> Unit,
    onSelectJunction: (Int) -> Unit,
    onOpenDurationSheet: () -> Unit,
    onDeselectAll: () -> Unit,
    onScrub: (Long) -> Unit,
    onAddClipsClick: () -> Unit,
    onAudioTrackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = FlowCutTheme.colors
    val isDark = isSystemInDarkTheme()
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    val totalMs = timeline.total
    val totalWidthDp = if (timeline.n > 0) (totalMs / 1000f) * DP_PER_SECOND else 0f

    var contextMenuClipIndex by remember { mutableStateOf<Int?>(null) }
    var isUserScrolling by remember { mutableStateOf(false) }

    val totalMsState = rememberUpdatedState(totalMs)
    val flingAnim = remember { Animatable(0f) }
    val decay = rememberSplineBasedDecay<Float>()
    val velocityTracker = remember { VelocityTracker() }
    var lastScrubMs by remember { mutableStateOf<Long?>(null) }
    var lastScrubAtNs by remember { mutableStateOf(0L) }

    fun requestScrub(targetMs: Long) {
        val nowNs = System.nanoTime()
        val last = lastScrubMs
        val shouldUpdate = last == null || abs(targetMs - last) >= 50L || (nowNs - lastScrubAtNs) >= 33_000_000L
        if (shouldUpdate) {
            lastScrubMs = targetMs
            lastScrubAtNs = nowNs
            onScrub(targetMs)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(224.dp)
            .pointerInput(density) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        scope.launch { flingAnim.stop() }
                        velocityTracker.resetTracking()
                        isUserScrolling = true
                    },
                    onDragEnd = {
                        isUserScrolling = false
                        val velocity = velocityTracker.calculateVelocity().x
                        if (abs(velocity) > 200f) {
                            scope.launch {
                                var lastVal = 0f
                                flingAnim.snapTo(0f)
                                flingAnim.animateDecay(velocity, decay) {
                                    val deltaPx = this.value - lastVal
                                    lastVal = this.value
                                    scrollState.dispatchRawDelta(-deltaPx)
                                    val newMs = ((scrollState.value / density.density) / DP_PER_SECOND * 1000f).toLong()
                                        .coerceIn(0L, maxOf(0L, totalMsState.value))
                                    requestScrub(newMs)
                                }
                            }
                        }
                    },
                    onDragCancel = { isUserScrolling = false },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        scrollState.dispatchRawDelta(-dragAmount)
                        val newMs = ((scrollState.value / density.density) / DP_PER_SECOND * 1000f).toLong()
                            .coerceIn(0L, maxOf(0L, totalMsState.value))
                        requestScrub(newMs)
                    }
                )
            }
    ) {
        val halfViewportDp = maxWidth / 2f
        val halfViewportPx = with(density) { halfViewportDp.toPx() }

        LaunchedEffect(currentMs, isUserScrolling, halfViewportPx) {
            if (!isUserScrolling && !flingAnim.isRunning) {
                val targetScrollDp = (currentMs / 1000f) * DP_PER_SECOND
                val targetScrollPx = with(density) { targetScrollDp.dp.toPx() }.toInt()
                if (abs(scrollState.value - targetScrollPx) > 1) {
                    scrollState.scrollTo(targetScrollPx)
                }
            }
        }

        val currentScrollDp = scrollState.value / density.density
        val visibleClips = if (timeline.n > 0) timeline.visibleRange(currentScrollDp, maxWidth.value) else IntRange.EMPTY
        val k0 = if (visibleClips.isEmpty()) 0 else maxOf(0, visibleClips.first - 2)
        val k1 = if (visibleClips.isEmpty()) -1 else minOf(timeline.n - 1, visibleClips.last + 2)

        val j0 = if (visibleClips.isEmpty()) 0 else maxOf(0, k0 - 1)
        val j1 = if (visibleClips.isEmpty()) -1 else minOf(timeline.n - 2, k1)

        Row(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(scrollState, enabled = false)
        ) {
            Spacer(modifier = Modifier.width(halfViewportDp))

            Box(
                modifier = Modifier
                    .width(maxOf(120.dp, totalWidthDp.dp + if (timeline.n < MAX_CLIPS) 60.dp else 0.dp))
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.surface1)
                    ) {
                        if (j1 >= j0) {
                            for (j in j0..j1) {
                                key("band_$j") {
                                    val junction = timeline.getJunctionLayout(j)
                                    if (junction.type != TransitionType.NONE && junction.durationMs > 0) {
                                        val bandLeft = junction.centerX - junction.bandWidth / 2f
                                        Box(
                                            modifier = Modifier
                                                .offset(x = bandLeft.dp)
                                                .width(junction.bandWidth.dp)
                                                .fillMaxHeight()
                                                .background(colors.accent.copy(alpha = 0.18f))
                                        )
                                    }
                                }
                            }
                        }

                        if (k1 >= k0) {
                            for (k in k0..k1) {
                                val clip = timeline.project.clips.getOrNull(k)
                                val clipKey = clip?.id ?: "clip_$k"
                                key(clipKey) {
                                    val layout = timeline.getClipLayout(k)
                                    val clipColors = getClipColors(k, isDark)
                                    val isSelected = selectedClipIndex == k

                                    Box(
                                        modifier = Modifier
                                            .offset(x = layout.leftDp.dp, y = 2.dp)
                                            .width(layout.widthDp.dp)
                                            .height(40.dp)
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(clipColors.bg)
                                            .then(
                                                if (isSelected) {
                                                    Modifier.border(2.dp, colors.accent, RoundedCornerShape(5.dp))
                                                } else Modifier
                                            )
                                            .combinedClickable(
                                                onClick = { onSelectClip(k) },
                                                onLongClick = { contextMenuClipIndex = k }
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Clip ${k + 1}",
                                            color = clipColors.text,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }

                        if (timeline.n < MAX_CLIPS) {
                            val addTileLeft = totalWidthDp + 8f
                            Box(
                                modifier = Modifier
                                    .offset(x = addTileLeft.dp)
                                    .size(44.dp)
                                    .combinedClickable(onClick = onAddClipsClick),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val stroke = Stroke(
                                        width = 1.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                                    )
                                    drawRoundRect(
                                        color = colors.borderStrong,
                                        size = size,
                                        cornerRadius = CornerRadius(6.dp.toPx()),
                                        style = stroke
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add clips",
                                    tint = colors.textPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        if (j1 >= j0) {
                            for (j in j0..j1) {
                                key("node_$j") {
                                    val junction = timeline.getJunctionLayout(j)
                                    val isSelected = selectedJunctionIndex == j
                                    val isApplied = junction.type != TransitionType.NONE

                                    val nodeBg = if (isSelected) colors.bgAccent else colors.surface2
                                    val nodeBorderColor = if (isSelected || isApplied) colors.accent else colors.borderStrong
                                    val nodeBorderWidth = if (isSelected) 2.dp else 1.5.dp
                                    val iconColor = if (isSelected || isApplied) colors.accent else colors.textMuted

                                    Box(
                                        modifier = Modifier
                                            .offset(x = (junction.centerX - 24f).dp, y = (-2).dp)
                                            .size(48.dp)
                                            .combinedClickable(
                                                onClick = {
                                                    if (isSelected && isApplied) {
                                                        onOpenDurationSheet()
                                                    } else {
                                                        onSelectJunction(j)
                                                    }
                                                }
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(22.dp)
                                                .clip(CircleShape)
                                                .background(nodeBg)
                                                .border(nodeBorderWidth, nodeBorderColor, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.SwapHoriz,
                                                contentDescription = "Transition",
                                                tint = iconColor,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.surface1)
                            .combinedClickable(onClick = onAudioTrackClick),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = "Audio track",
                            tint = colors.textSecondary,
                            modifier = Modifier
                                .padding(start = 10.dp)
                                .size(14.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(halfViewportDp))
        }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(2.dp)
                .fillMaxHeight()
                .background(colors.textPrimary, RoundedCornerShape(1.dp))
        )

        contextMenuClipIndex?.let { index ->
            DropdownMenu(
                expanded = true,
                onDismissRequest = { contextMenuClipIndex = null }
            ) {
                DropdownMenuItem(
                    text = { Text("Delete", color = colors.danger) },
                    onClick = {
                        contextMenuClipIndex = null
                        onDeleteClip(index)
                    }
                )
            }
        }
    }
}
