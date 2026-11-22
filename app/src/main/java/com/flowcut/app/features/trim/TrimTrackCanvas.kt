/**
 * Feature:     trim
 * File role:   ui
 * Responsible: Canvas rendering for trim filmstrip track and drag handle interactions
 * Depends on:  shared
 * Used by:     TrimUi
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.trim

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong

@Composable
fun TrimTrackCanvas(
    tempInUs: Long,
    tempOutUs: Long,
    maxSourceUs: Long,
    isGlobal: Boolean,
    junctionTicksUs: List<Long>,
    onTrimChange: (newInUs: Long, newOutUs: Long, isLeft: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    trackMarginDp: Dp = 28.dp,
    trackHeightDp: Dp = 56.dp
) {
    val density = LocalDensity.current.density

    val whiteSelectionStroke = remember(density) { Stroke(width = 2.5f * density) }
    val chevronStroke = remember(density) { Stroke(width = 2f * density, cap = StrokeCap.Round) }
    val handleCornerRadiusPx = remember(density) { 5f * density }
    val handleWidthPx = remember(density) { 15f * density }
    val handleHeightPx = remember(density) { 38f * density }
    val chevronHalfWPx = remember(density) { 2.5f * density }
    val chevronHalfHPx = remember(density) { 5.5f * density }
    val cornerRadiusPx = remember(density) { 6f * density }

    val trackOutlinePath = remember { Path() }
    val leftHandlePath = remember { Path() }
    val rightHandlePath = remember { Path() }
    val leftChevronPath = remember { Path() }
    val rightChevronPath = remember { Path() }

    Canvas(
        modifier = modifier
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
                                val (newIn, _) = TrimLogic.clampTrimRange(startInUs + deltaUs, tempOutUs, maxSourceUs)
                                if (newIn != tempInUs) {
                                    onTrimChange(newIn, tempOutUs, true)
                                }
                            } else {
                                val (_, newOut) = TrimLogic.clampTrimRange(tempInUs, startOutUs + deltaUs, maxSourceUs)
                                if (newOut != tempOutUs) {
                                    onTrimChange(tempInUs, newOut, false)
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

        clipPath(trackOutlinePath) {
            drawRect(
                color = Color(0xFF2C3E50),
                topLeft = Offset(trackLeft, trackTop),
                size = Size(trackW, trackBottom - trackTop)
            )

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

            if (isGlobal && junctionTicksUs.isNotEmpty()) {
                for (tickUs in junctionTicksUs) {
                    val tickX = trackLeft + ((tickUs.toDouble() / maxSourceUs) * (trackRight - trackLeft)).toFloat()
                    drawLine(
                        color = Color(0x66FFFFFF),
                        start = Offset(tickX, trackTop + 4f * density),
                        end = Offset(tickX, trackBottom - 4f * density),
                        strokeWidth = 1.5f * density
                    )
                }
            }

            if (sLeftX > trackLeft) {
                drawRect(
                    color = Color(0xB3000000),
                    topLeft = Offset(trackLeft, trackTop),
                    size = Size(sLeftX - trackLeft, trackBottom - trackTop)
                )
            }

            if (sRightX < trackRight) {
                drawRect(
                    color = Color(0xB3000000),
                    topLeft = Offset(sRightX, trackTop),
                    size = Size(trackRight - sRightX, trackBottom - trackTop)
                )
            }
        }

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

        val clipCenterY = (trackTop + trackBottom) / 2f
        val handleTop = clipCenterY - handleHeightPx / 2f
        val handleBottom = clipCenterY + handleHeightPx / 2f
        val r = minOf(handleCornerRadiusPx, handleWidthPx / 2f, (handleBottom - handleTop) / 2f)

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

        val leftHandleCenterX = sLeftX - (handleWidthPx / 2f)
        leftChevronPath.reset()
        leftChevronPath.moveTo(leftHandleCenterX + chevronHalfWPx, clipCenterY - chevronHalfHPx)
        leftChevronPath.lineTo(leftHandleCenterX - chevronHalfWPx, clipCenterY)
        leftChevronPath.lineTo(leftHandleCenterX + chevronHalfWPx, clipCenterY + chevronHalfHPx)
        drawPath(path = leftChevronPath, color = Color(0xFF141414), style = chevronStroke)

        val rightHandleCenterX = sRightX + (handleWidthPx / 2f)
        rightChevronPath.reset()
        rightChevronPath.moveTo(rightHandleCenterX - chevronHalfWPx, clipCenterY - chevronHalfHPx)
        rightChevronPath.lineTo(rightHandleCenterX + chevronHalfWPx, clipCenterY)
        rightChevronPath.lineTo(rightHandleCenterX - chevronHalfWPx, clipCenterY + chevronHalfHPx)
        drawPath(path = rightChevronPath, color = Color(0xFF141414), style = chevronStroke)
    }
}
