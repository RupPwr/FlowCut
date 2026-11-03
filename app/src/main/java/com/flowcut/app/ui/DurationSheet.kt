package com.flowcut.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowcut.app.model.TransitionType
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DurationSheet(
    transitionType: TransitionType,
    currentDurationMs: Long,
    maxDurationMs: Long,
    onDurationChange: (Long) -> Unit,
    onDurationChangeFinished: (Long) -> Unit,
    onRemoveTransition: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = FlowCutTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val minSec = 0.3f
    val maxSec = maxOf(minSec, maxDurationMs / 1000f)
    val initialSec = (currentDurationMs / 1000f).coerceIn(minSec, maxSec)

    var sliderValue by remember(currentDurationMs) { mutableFloatStateOf(initialSec) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface0
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // Title: "{Type name} · {d} s" (16 sp medium)
            val formattedSec = String.format(Locale.US, "%.1f", sliderValue)
            Text(
                text = "${transitionType.displayName} · $formattedSec s",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Slider: min 0.3, max = maxDuration, step 0.1 s
            val steps = ((maxSec - minSec) / 0.1f).roundToInt() - 1
            Slider(
                value = sliderValue,
                onValueChange = { newVal ->
                    val rounded = (newVal * 10f).roundToInt() / 10f
                    sliderValue = rounded
                    onDurationChange((rounded * 1000).toLong())
                },
                onValueChangeFinished = {
                    val finalMs = (sliderValue * 1000).toLong()
                    onDurationChangeFinished(finalMs)
                },
                valueRange = minSec..maxSec,
                steps = if (steps > 0) steps else 0,
                colors = SliderDefaults.colors(
                    thumbColor = colors.accent,
                    activeTrackColor = colors.accent,
                    inactiveTrackColor = colors.borderStrong
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Buttons: left "Remove transition" (danger text button), right "Done" (filled button)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        onRemoveTransition()
                        onDismiss()
                    }
                ) {
                    Text(
                        text = "Remove transition",
                        color = colors.danger,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.onAccent
                    )
                ) {
                    Text(
                        text = "Done",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
