package com.flowcut.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowcut.app.core.edit.SetTimeMapCommand
import com.flowcut.app.core.model.map
import com.flowcut.app.core.time.TimeMap
import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.time.usToMs
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.core.timeline.TimelineSnapshot
import com.flowcut.app.engine.FrameDriver
import com.flowcut.app.engine.PreviewRenderer
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Dedicated Trim BottomSheet / Panel for the selected clip.
 *
 * Provides full-clip untrimmed source visualization with CapCut-style crisp white
 * selection border and '<' / '>' pill chevron trim handles.
 * Real-time video preview updates live via [PreviewRenderer.trimPreviewTimeUs] and [FrameDriver.wake].
 */
@Composable
fun TrimSheet(
    clipIndex: Int,
    snapshotHolder: SnapshotHolder,
    previewRenderer: PreviewRenderer,
    frameDriver: FrameDriver,
    getClipNaturalDurationUs: (Int) -> Long,
    onCommitTrim: (clipIndex: Int, newInUs: Long, newOutUs: Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = FlowCutTheme.colors
    val density = LocalDensity.current.density

    // Capture unmodified snapshot at moment TrimSheet is opened
    val originalSnapshot = remember { snapshotHolder.current }

    val clip = originalSnapshot.project.clips.getOrNull(clipIndex) ?: run {
        onDismiss()
        return
    }

    val initialMap = remember(clip) { clip.map() }
    val maxSourceUs = remember(clipIndex, clip, initialMap) {
        val reported = getClipNaturalDurationUs(clipIndex)
        maxOf(initialMap.outUs, reported, msToUs(clip.durationMs), 200_000L)
    }

    var tempInUs by remember {
        mutableLongStateOf(initialMap.inUs.coerceIn(0L, maxOf(0L, initialMap.outUs - 200_000L)))
    }
    var tempOutUs by remember {
        mutableLongStateOf(initialMap.outUs.coerceIn(tempInUs + 200_000L, maxSourceUs))
    }

    // Wakes frame driver and positions preview on the clip start frame upon opening
    LaunchedEffect(clipIndex) {
        val startEdgeUs = msToUs(snapshotHolder.current.main.startMs.getOrElse(clipIndex) { 0L })
        previewRenderer.trimPreviewTimeUs = startEdgeUs
        frameDriver.wake()
    }

    // Guaranteed cleanup on dismiss / unmount
    DisposableEffect(Unit) {
        onDispose {
            previewRenderer.trimPreviewTimeUs = -1L
            frameDriver.wake()
        }
    }

    val handleClose = {
        previewRenderer.trimPreviewTimeUs = -1L
        snapshotHolder.current = originalSnapshot
        frameDriver.wake()
        onDismiss()
    }

    val handleDone = {
        previewRenderer.trimPreviewTimeUs = -1L
        onCommitTrim(clipIndex, tempInUs, tempOutUs)
        frameDriver.wake()
        onDismiss()
    }

    // Intercept system back gesture to discard temporary trim and restore snapshot
    BackHandler {
        handleClose()
    }

    // Updates SnapshotHolder.current and triggers live video preview render
    val updateSnapshotAndPreview: (Long, Long, Boolean) -> Unit = { inUs, outUs, isLeft ->
        val speedNum = initialMap.speedNum
        val speedDen = initialMap.speedDen
        val newMap = TimeMap(
            inUs = inUs,
            outUs = outUs,
            speedNum = speedNum,
            speedDen = speedDen
        )
        val newProject = SetTimeMapCommand(
            clipIndex = clipIndex,
            timeMap = newMap
        ).apply(snapshotHolder.current.project)
        snapshotHolder.current = TimelineSnapshot(System.nanoTime(), newProject)

        val trimmedEdgeUs = if (isLeft) {
            msToUs(snapshotHolder.current.main.startMs.getOrElse(clipIndex) { 0L })
        } else {
            val endMs = snapshotHolder.current.main.endMs.getOrElse(clipIndex) { 0L }
            val startMs = snapshotHolder.current.main.startMs.getOrElse(clipIndex) { 0L }
            maxOf(msToUs(startMs), msToUs(endMs) - 1_000L)
        }
        previewRenderer.trimPreviewTimeUs = trimmedEdgeUs
        frameDriver.wake()
    }

    // Pre-allocated styling constants (Rule R10 & Section 8 alignment)
    val whiteSelectionStroke = remember(density) { Stroke(width = 2.5f * density) }
    val chevronStroke = remember(density) { Stroke(width = 2f * density, cap = StrokeCap.Round) }
    val handleCornerRadiusPx = remember(density) { 5f * density }
    val handleWidthPx = remember(density) { 15f * density }
    val handleHeightPx = remember(density) { 38f * density }
    val chevronHalfWPx = remember(density) { 2.5f * density }
    val chevronHalfHPx = remember(density) { 5.5f * density }
    val cornerRadiusPx = remember(density) { 6f * density }

    // Reusable paths for zero allocation in onDraw
    val trackOutlinePath = remember { Path() }
    val leftHandlePath = remember { Path() }
    val rightHandlePath = remember { Path() }
    val leftChevronPath = remember { Path() }
    val rightChevronPath = remember { Path() }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // Scrim above sheet: dismisses without modifying project on outside click
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { handleClose() }
        )

        // Bottom Sheet Surface container
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { /* Consume touch events inside sheet */ },
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = colors.surface0,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(top = 10.dp, bottom = 16.dp)
            ) {
                // 1. Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Top-Left Close (✕) button
                    IconButton(onClick = handleClose) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Discard trim and close",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Center Title & Live Selected Duration
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Trim Clip",
                            color = colors.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${formatDurationUs(tempOutUs - tempInUs)} / ${formatDurationUs(maxSourceUs)}",
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }

                    // Top-Right Done (✓) button
                    IconButton(onClick = handleDone) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Commit trim",
                            tint = colors.accent,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 2. Full-Clip Trim Track with Crisp White Border and Pill Chevron Handles
                val trackMarginDp = 28.dp
                val trackHeightDp = 56.dp

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .pointerInput(maxSourceUs) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val trackMarginPx = trackMarginDp.toPx()
                                val trackW = maxOf(1f, size.width - 2f * trackMarginPx)

                                val sLeftX = trackMarginPx + ((tempInUs.toDouble() / maxSourceUs) * trackW).toFloat()
                                val sRightX = trackMarginPx + ((tempOutUs.toDouble() / maxSourceUs) * trackW).toFloat()

                                val downX = down.position.x
                                val leftDist = kotlin.math.abs(downX - sLeftX)
                                val rightDist = kotlin.math.abs(downX - sRightX)

                                val isLeftHandle = when {
                                    downX <= sLeftX -> true
                                    downX >= sRightX -> false
                                    else -> leftDist <= rightDist
                                }

                                val startTouchX = downX
                                val startInUs = tempInUs
                                val startOutUs = tempOutUs

                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.pressed) {
                                        val dx = change.position.x - startTouchX
                                        val deltaUs = ((dx / trackW).toDouble() * maxSourceUs).roundToLong()

                                        if (isLeftHandle) {
                                            val newInUs = (startInUs + deltaUs).coerceIn(0L, tempOutUs - 200_000L)
                                            if (newInUs != tempInUs) {
                                                tempInUs = newInUs
                                                updateSnapshotAndPreview(newInUs, tempOutUs, true)
                                            }
                                        } else {
                                            val newOutUs = (startOutUs + deltaUs).coerceIn(tempInUs + 200_000L, maxSourceUs)
                                            if (newOutUs != tempOutUs) {
                                                tempOutUs = newOutUs
                                                updateSnapshotAndPreview(tempInUs, newOutUs, false)
                                            }
                                        }
                                        change.consume()
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                ) {
                    val trackMarginPx = trackMarginDp.toPx()
                    val trackW = maxOf(1f, size.width - 2f * trackMarginPx)
                    val trackTop = 8f * density
                    val trackBottom = trackTop + trackHeightDp.toPx()
                    val trackLeft = trackMarginPx
                    val trackRight = trackLeft + trackW

                    val sLeftX = trackLeft + ((tempInUs.toDouble() / maxSourceUs) * trackW).toFloat()
                    val sRightX = trackLeft + ((tempOutUs.toDouble() / maxSourceUs) * trackW).toFloat()

                    // Build outline for clipping the track contents to 6dp rounded corners
                    trackOutlinePath.reset()
                    trackOutlinePath.addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left = trackLeft,
                            top = trackTop,
                            right = trackRight,
                            bottom = trackBottom,
                            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                        )
                    )

                    // Clip background and filmstrip cells
                    clipPath(trackOutlinePath) {
                        // Track background
                        drawRect(
                            color = Color(0xFF2C3E50),
                            topLeft = Offset(trackLeft, trackTop),
                            size = Size(trackW, trackBottom - trackTop)
                        )

                        // Filmstrip cell dividers
                        val cellW = 44f * density
                        var cx = trackLeft + cellW
                        while (cx < trackRight) {
                            drawLine(
                                color = Color(0x2EFFFFFF),
                                start = Offset(cx, trackTop),
                                end = Offset(cx, trackBottom),
                                strokeWidth = 1f * density
                            )
                            cx += cellW
                        }

                        // Sprocket holes along top and bottom edges
                        val sprocketW = 5f * density
                        val sprocketH = 3.5f * density
                        val sprocketSpacing = 16f * density
                        var sx = trackLeft + 8f * density
                        while (sx < trackRight - 8f * density) {
                            drawRect(
                                color = Color(0x44FFFFFF),
                                topLeft = Offset(sx, trackTop + 2.5f * density),
                                size = Size(sprocketW, sprocketH)
                            )
                            drawRect(
                                color = Color(0x44FFFFFF),
                                topLeft = Offset(sx, trackBottom - 2.5f * density - sprocketH),
                                size = Size(sprocketW, sprocketH)
                            )
                            sx += sprocketSpacing
                        }

                        // Dimmed overlay on trimmed-out left region [trackLeft .. sLeftX]
                        if (sLeftX > trackLeft) {
                            drawRect(
                                color = Color(0xB3000000),
                                topLeft = Offset(trackLeft, trackTop),
                                size = Size(sLeftX - trackLeft, trackBottom - trackTop)
                            )
                        }

                        // Dimmed overlay on trimmed-out right region [sRightX .. trackRight]
                        if (sRightX < trackRight) {
                            drawRect(
                                color = Color(0xB3000000),
                                topLeft = Offset(sRightX, trackTop),
                                size = Size(trackRight - sRightX, trackBottom - trackTop)
                            )
                        }
                    }

                    // Crisp White Selection Border around [sLeftX .. sRightX]
                    val halfStroke = whiteSelectionStroke.width / 2f
                    val bLeft = sLeftX + halfStroke
                    val bRight = maxOf(bLeft, sRightX - halfStroke)
                    val bTop = trackTop + halfStroke
                    val bBottom = trackBottom - halfStroke
                    val bRadius = maxOf(0f, cornerRadiusPx - halfStroke)

                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(bLeft, bTop),
                        size = Size(bRight - bLeft, bBottom - bTop),
                        cornerRadius = CornerRadius(bRadius, bRadius),
                        style = whiteSelectionStroke
                    )

                    // Vertical center of track
                    val clipCenterY = (trackTop + trackBottom) / 2f
                    val handleTop = clipCenterY - handleHeightPx / 2f
                    val handleBottom = clipCenterY + handleHeightPx / 2f
                    val r = minOf(handleCornerRadiusPx, handleWidthPx / 2f, (handleBottom - handleTop) / 2f)

                    // Left Pill Handle Tab
                    val leftHandleLeft = sLeftX - handleWidthPx
                    val leftHandleRight = sLeftX + 1f
                    leftHandlePath.reset()
                    leftHandlePath.moveTo(leftHandleRight, handleTop)
                    leftHandlePath.lineTo(leftHandleLeft + r, handleTop)
                    leftHandlePath.quadraticTo(leftHandleLeft, handleTop, leftHandleLeft, handleTop + r)
                    leftHandlePath.lineTo(leftHandleLeft, handleBottom - r)
                    leftHandlePath.quadraticTo(leftHandleLeft, handleBottom, leftHandleLeft + r, handleBottom)
                    leftHandlePath.lineTo(leftHandleRight, handleBottom)
                    leftHandlePath.close()
                    drawPath(path = leftHandlePath, color = Color.White)

                    // Right Pill Handle Tab
                    val rightHandleLeft = sRightX - 1f
                    val rightHandleRight = sRightX + handleWidthPx
                    rightHandlePath.reset()
                    rightHandlePath.moveTo(rightHandleLeft, handleTop)
                    rightHandlePath.lineTo(rightHandleRight - r, handleTop)
                    rightHandlePath.quadraticTo(rightHandleRight, handleTop, rightHandleRight, handleTop + r)
                    rightHandlePath.lineTo(rightHandleRight, handleBottom - r)
                    rightHandlePath.quadraticTo(rightHandleRight, handleBottom, rightHandleRight - r, handleBottom)
                    rightHandlePath.lineTo(rightHandleLeft, handleBottom)
                    rightHandlePath.close()
                    drawPath(path = rightHandlePath, color = Color.White)

                    // Sharp dark/black < Chevron on Left Handle
                    val leftHandleCenterX = sLeftX - (handleWidthPx / 2f)
                    leftChevronPath.reset()
                    leftChevronPath.moveTo(leftHandleCenterX + chevronHalfWPx, clipCenterY - chevronHalfHPx)
                    leftChevronPath.lineTo(leftHandleCenterX - chevronHalfWPx, clipCenterY)
                    leftChevronPath.lineTo(leftHandleCenterX + chevronHalfWPx, clipCenterY + chevronHalfHPx)
                    drawPath(path = leftChevronPath, color = Color(0xFF141414), style = chevronStroke)

                    // Sharp dark/black > Chevron on Right Handle
                    val rightHandleCenterX = sRightX + (handleWidthPx / 2f)
                    rightChevronPath.reset()
                    rightChevronPath.moveTo(rightHandleCenterX - chevronHalfWPx, clipCenterY - chevronHalfHPx)
                    rightChevronPath.lineTo(rightHandleCenterX + chevronHalfWPx, clipCenterY)
                    rightChevronPath.lineTo(rightHandleCenterX - chevronHalfWPx, clipCenterY + chevronHalfHPx)
                    drawPath(path = rightChevronPath, color = Color(0xFF141414), style = chevronStroke)
                }

                // 3. Start and End Time Indicators below track
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = trackMarginDp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "0:00.0",
                        color = colors.textSecondary,
                        fontSize = 11.sp
                    )
                    Text(
                        text = formatDurationUs(maxSourceUs),
                        color = colors.textSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

/**
 * Formats microseconds to "M:SS.T" (e.g. "0:02.4" or "0:10.0").
 */
private fun formatDurationUs(timeUs: Long): String {
    val totalMs = (timeUs / 1000L).coerceAtLeast(0L)
    val tenths = (totalMs % 1000L) / 100L
    val totalSec = totalMs / 1000L
    val sec = totalSec % 60L
    val min = totalSec / 60L
    return String.format(Locale.US, "%d:%02d.%d", min, sec, tenths)
}
