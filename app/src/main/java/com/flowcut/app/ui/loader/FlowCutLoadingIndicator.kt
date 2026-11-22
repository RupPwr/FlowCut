package com.flowcut.app.ui.loader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Standard Android loading indicator with CircularProgressIndicator and optional text label.
 * Styled with a sleek, semi-transparent dark rounded badge for high visibility over any video frame.
 */
@Composable
fun FlowCutLoadingIndicator(
    modifier: Modifier = Modifier,
    message: String? = "Processing...",
    isVisible: Boolean = true
) {
    if (!isVisible) return

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.70f), RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                color = Color.White,
                strokeWidth = 3.dp
            )
            if (!message.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = message,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
