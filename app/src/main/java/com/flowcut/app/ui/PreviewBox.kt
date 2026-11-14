package com.flowcut.app.ui

import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun PreviewBox(
    hasClips: Boolean,
    selectedTransitionName: String?,
    onAddVideoClick: () -> Unit,
    modifier: Modifier = Modifier,
    glSurfaceView: View? = null,
    targetCanvasAspect: Float = 9f / 16f,
    aspectRatioLabel: String = "9:16",
    onRatioClick: (() -> Unit)? = null
) {
    val colors = FlowCutTheme.colors

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(colors.surface1)
    ) {
        if (!hasClips) {
            // Empty state: "Add video" button
            OutlinedButton(
                onClick = onAddVideoClick,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = colors.accent
                ),
                border = BorderStroke(1.dp, colors.accent),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Icon(
                    imageVector = Icons.Default.VideoLibrary,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = "Add video",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            // Centered aspect-locked canvas matching targetCanvasAspect
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val containerW = maxWidth
                val containerH = maxHeight
                val containerAspect = if (containerH > 0.dp) containerW / containerH else 1f
                val canvasMod = if (targetCanvasAspect > containerAspect) {
                    Modifier.fillMaxWidth().aspectRatio(targetCanvasAspect)
                } else {
                    Modifier.fillMaxHeight().aspectRatio(targetCanvasAspect)
                }
                Box(
                    modifier = canvasMod
                        .clip(RectangleShape)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (glSurfaceView != null) {
                        AndroidView(
                            factory = { glSurfaceView },
                            update = { view ->
                                (view as? android.view.SurfaceView)?.holder?.setSizeFromLayout()
                                view.requestLayout()
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        // Top-left label: transition name if junction selected & transition not NONE
        if (!selectedTransitionName.isNullOrBlank() && selectedTransitionName != "None") {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(colors.surface2, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = selectedTransitionName,
                    color = colors.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Top-right label: dynamic aspect ratio badge
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(colors.surface2, RoundedCornerShape(6.dp))
                .clickable(enabled = onRatioClick != null) { onRatioClick?.invoke() }
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = aspectRatioLabel,
                color = colors.textPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
