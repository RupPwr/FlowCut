package com.flowcut.app.ui

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.flowcut.app.engine.OverlayItem
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.Random
import kotlin.math.roundToInt

@Composable
internal fun CoverSelectionSheet(
    onSelectCurrentFrame: () -> Unit,
    onChooseGallery: () -> Unit,
    onDismiss: () -> Unit,
    currentPlayheadTimeFormatted: String
) {
    val colors = FlowCutTheme.colors

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {},
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = colors.surface0,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(0.5.dp, colors.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Select Cover",
                        color = colors.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Option 1: Use Current Frame
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface1, RoundedCornerShape(10.dp))
                        .clickable { onSelectCurrentFrame() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.VideoFile,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Use Current Frame",
                            color = colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Set cover to timeline video frame at $currentPlayheadTimeFormatted",
                            color = colors.textSecondary,
                            fontSize = 11.5.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Option 2: Choose from Gallery
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface1, RoundedCornerShape(10.dp))
                        .clickable { onChooseGallery() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Choose from Gallery",
                            color = colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Pick a custom photo or thumbnail from your device",
                            color = colors.textSecondary,
                            fontSize = 11.5.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

internal fun copyUriToCacheFile(context: Context, uri: Uri, prefix: String): String? {
    return try {
        val extension = context.contentResolver.getType(uri)?.substringAfterLast('/')?.let { ".$it" } ?: ".jpg"
        val cacheFile = File(context.cacheDir, "${prefix}${System.currentTimeMillis()}${extension}")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        }
        cacheFile.absolutePath
    } catch (_: Exception) {
        null
    }
}

private data class SoundBeat(val id: String, val title: String, val genre: String, val durationSec: Float = 5.0f)
private data class SoundFx(val id: String, val title: String, val durationSec: Float)

@Composable
internal fun AudioSoundsSheet(
    context: Context,
    onAddSound: (path: String, title: String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = FlowCutTheme.colors
    val beats = remember {
        listOf(
            SoundBeat("pop", "Upbeat Pop", "Bright Pop • 120 BPM"),
            SoundBeat("chill", "Lo-Fi Chill", "Mellow Piano • 80 BPM"),
            SoundBeat("cinematic", "Cinematic Pulse", "Dramatic Pulse • 100 BPM"),
            SoundBeat("trap", "Trap Beat", "Energetic Trap • 130 BPM"),
            SoundBeat("acoustic", "Acoustic Warmth", "Gentle Acoustic • 90 BPM"),
            SoundBeat("synthwave", "Synthwave", "Retro 80s Synth • 110 BPM")
        )
    }
    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var previewingId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try { previewPlayer?.release() } catch (_: Exception) {}
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {},
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = colors.surface0,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(0.5.dp, colors.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sound Collection",
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.textPrimary)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                beats.forEach { beat ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            val isPlaying = previewingId == beat.id
                            IconButton(
                                onClick = {
                                    if (isPlaying) {
                                        try { previewPlayer?.stop(); previewPlayer?.release() } catch (_: Exception) {}
                                        previewPlayer = null
                                        previewingId = null
                                    } else {
                                        try { previewPlayer?.release() } catch (_: Exception) {}
                                        val path = generateSynthesizedAudioFile(context, beat.id, beat.genre, beat.title, beat.durationSec)
                                        val mp = MediaPlayer().apply {
                                            setDataSource(path)
                                            prepare()
                                            start()
                                            setOnCompletionListener {
                                                previewingId = null
                                            }
                                        }
                                        previewPlayer = mp
                                        previewingId = beat.id
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = "Preview",
                                    tint = colors.accent
                                )
                            }
                            Column {
                                Text(beat.title, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(beat.genre, color = colors.textSecondary, fontSize = 11.sp)
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = colors.accent,
                            modifier = Modifier.clickable {
                                try { previewPlayer?.release() } catch (_: Exception) {}
                                val path = generateSynthesizedAudioFile(context, beat.id, beat.genre, beat.title, beat.durationSec)
                                onAddSound(path, beat.title)
                            }
                        ) {
                            Text(
                                text = "USE",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AudioEffectsSheet(
    context: Context,
    onAddEffect: (path: String, title: String, durSec: Float) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = FlowCutTheme.colors
    val effects = remember {
        listOf(
            SoundFx("whoosh", "Whoosh", 0.6f),
            SoundFx("pop_fx", "Pop", 0.2f),
            SoundFx("ding", "Ding", 1.0f),
            SoundFx("applause", "Applause", 1.5f),
            SoundFx("swoosh", "Swoosh", 0.4f),
            SoundFx("glitch", "Glitch", 0.5f),
            SoundFx("bassdrop", "Bass Drop", 1.2f),
            SoundFx("beep", "Beep", 0.3f)
        )
    }
    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var previewingId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try { previewPlayer?.release() } catch (_: Exception) {}
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {},
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = colors.surface0,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(0.5.dp, colors.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sound Effects",
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.textPrimary)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                effects.chunked(2).forEach { rowFxs ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowFxs.forEach { fx ->
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        try { previewPlayer?.release() } catch (_: Exception) {}
                                        val path = generateSynthesizedAudioFile(context, fx.id, "fx", fx.title, fx.durationSec)
                                        onAddEffect(path, fx.title, fx.durationSec)
                                    },
                                shape = RoundedCornerShape(8.dp),
                                color = colors.surface1,
                                border = BorderStroke(1.dp, colors.border)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    val isPlaying = previewingId == fx.id
                                    IconButton(
                                        modifier = Modifier.size(28.dp),
                                        onClick = {
                                            if (isPlaying) {
                                                try { previewPlayer?.stop(); previewPlayer?.release() } catch (_: Exception) {}
                                                previewPlayer = null
                                                previewingId = null
                                            } else {
                                                try { previewPlayer?.release() } catch (_: Exception) {}
                                                val path = generateSynthesizedAudioFile(context, fx.id, "fx", fx.title, fx.durationSec)
                                                val mp = MediaPlayer().apply {
                                                    setDataSource(path)
                                                    prepare()
                                                    start()
                                                    setOnCompletionListener {
                                                        previewingId = null
                                                    }
                                                }
                                                previewPlayer = mp
                                                previewingId = fx.id
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                            contentDescription = "Preview",
                                            tint = colors.accent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
                                        Text(fx.title, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        Text("${fx.durationSec}s", color = colors.textSecondary, fontSize = 10.sp)
                                    }
                                    Text("ADD", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AudioVoiceRecordSheet(
    context: Context,
    onAddRecording: (path: String, durationUs: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = FlowCutTheme.colors
    var isRecording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var activeRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordFile by remember { mutableStateOf<File?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            errorMessage = "Microphone permission is required to record voiceover."
            Toast.makeText(context, "Microphone permission required for voice recording", Toast.LENGTH_SHORT).show()
        } else {
            errorMessage = null
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            val start = System.currentTimeMillis()
            while (isRecording) {
                elapsedMs = System.currentTimeMillis() - start
                delay(50L)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                activeRecorder?.stop()
            } catch (_: Exception) {}
            try {
                activeRecorder?.release()
            } catch (_: Exception) {}
            activeRecorder = null
        }
    }

    fun createAndStartRecorder(outFile: File, sampleRate: Int): MediaRecorder {
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(128_000)
            mr.setAudioSamplingRate(sampleRate)
            mr.setOutputFile(outFile.absolutePath)
            mr.prepare()
            mr.start()
            return mr
        } catch (e: Exception) {
            try { mr.release() } catch (_: Exception) {}
            throw e
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    if (isRecording) {
                        try { activeRecorder?.stop() } catch (_: Exception) {}
                        try { activeRecorder?.release() } catch (_: Exception) {}
                        activeRecorder = null
                        recordFile?.delete()
                    }
                    onDismiss()
                }
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {},
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = colors.surface0,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(0.5.dp, colors.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Voiceover Recording",
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = {
                        if (isRecording) {
                            try { activeRecorder?.stop() } catch (_: Exception) {}
                            try { activeRecorder?.release() } catch (_: Exception) {}
                            activeRecorder = null
                            recordFile?.delete()
                        }
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.textPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Timer text: "00:03.2"
                val min = elapsedMs / 60000L
                val sec = (elapsedMs % 60000L) / 1000.0
                val timerStr = String.format(Locale.US, "%02d:%04.1f", min, sec)
                Text(
                    text = timerStr,
                    color = if (isRecording) colors.accent else colors.textPrimary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage ?: if (isRecording) "Recording... Tap to Stop & Add" else "Tap red button to record",
                    color = if (errorMessage != null) Color(0xFFEF4444) else colors.textSecondary,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Big circular Record / Stop button
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(Color(0xFFEF4444), RoundedCornerShape(36.dp))
                        .clickable {
                            if (!isRecording) {
                                val hasPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    android.Manifest.permission.RECORD_AUDIO
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                                if (!hasPerm) {
                                    permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                    return@clickable
                                }

                                errorMessage = null
                                elapsedMs = 0L
                                val outFile = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
                                recordFile = outFile
                                val startedRecorder = try {
                                    // Prefer 48 kHz to match FlowCut's native AudioEngine; fall back to 44.1 kHz on devices that require it.
                                    try {
                                        createAndStartRecorder(outFile, 48_000)
                                    } catch (_: Exception) {
                                        if (outFile.exists()) outFile.delete()
                                        createAndStartRecorder(outFile, 44_100)
                                    }
                                } catch (_: Exception) {
                                    if (outFile.exists()) outFile.delete()
                                    recordFile = null
                                    null
                                }

                                if (startedRecorder != null) {
                                    activeRecorder = startedRecorder
                                    isRecording = true
                                } else {
                                    errorMessage = "Microphone unavailable. Close other recording apps and try again."
                                    Toast.makeText(context, "Could not start microphone recording", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                isRecording = false
                                var stopSucceeded = true
                                try {
                                    activeRecorder?.stop()
                                } catch (_: Exception) {
                                    stopSucceeded = false
                                }
                                try {
                                    activeRecorder?.release()
                                } catch (_: Exception) {}
                                activeRecorder = null

                                val finalFile = recordFile
                                val recordedMs = elapsedMs
                                val durUs = (recordedMs * 1000L).coerceAtLeast(OverlayItem.MIN_DURATION_US)
                                if (stopSucceeded && recordedMs >= 200L && finalFile != null && finalFile.exists() && finalFile.length() > 512L) {
                                    onAddRecording(finalFile.absolutePath, durUs)
                                } else {
                                    try { finalFile?.delete() } catch (_: Exception) {}
                                    recordFile = null
                                    elapsedMs = 0L
                                    errorMessage = "Recording was too short or failed. Please record for at least 0.5s."
                                    Toast.makeText(context, "Recording failed or too short — please try again", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isRecording) {
                        Box(modifier = Modifier.size(24.dp).background(Color.White, RoundedCornerShape(4.dp)))
                    } else {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Record",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
internal fun AudioFadeSheet(
    item: OverlayItem,
    onUpdateFade: (fadeInUs: Long, fadeOutUs: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = FlowCutTheme.colors
    val maxFadeSec = ((item.endUs - item.startUs) / 2_000_000.0f).coerceIn(0.1f, 5.0f)
    var fadeInSec by remember(item.id) { mutableFloatStateOf((item.fadeInUs / 1_000_000f).coerceIn(0f, maxFadeSec)) }
    var fadeOutSec by remember(item.id) { mutableFloatStateOf((item.fadeOutUs / 1_000_000f).coerceIn(0f, maxFadeSec)) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {},
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = colors.surface0,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(0.5.dp, colors.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.textPrimary)
                    }
                    Text(
                        text = "Fade",
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = {
                        onUpdateFade((fadeInSec * 1_000_000L).toLong(), (fadeOutSec * 1_000_000L).toLong())
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Check, contentDescription = "Done", tint = colors.accent)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Fade In Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Fade in", color = colors.textPrimary, fontSize = 14.sp)
                    Text(String.format(Locale.US, "%.1fs", fadeInSec), color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = fadeInSec,
                    onValueChange = {
                        fadeInSec = it
                        onUpdateFade((it * 1_000_000L).toLong(), (fadeOutSec * 1_000_000L).toLong())
                    },
                    valueRange = 0f..maxFadeSec,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accent,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.border
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Fade Out Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Fade out", color = colors.textPrimary, fontSize = 14.sp)
                    Text(String.format(Locale.US, "%.1fs", fadeOutSec), color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = fadeOutSec,
                    onValueChange = {
                        fadeOutSec = it
                        onUpdateFade((fadeInSec * 1_000_000L).toLong(), (it * 1_000_000L).toLong())
                    },
                    valueRange = 0f..maxFadeSec,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accent,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.border
                    )
                )
            }
        }
    }
}

@Composable
internal fun AudioVolumeSheet(
    item: OverlayItem,
    onUpdateVolume: (volume: Float) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = FlowCutTheme.colors
    var volume by remember(item.id) { mutableFloatStateOf(item.volume) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {},
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = colors.surface0,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(0.5.dp, colors.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.textPrimary)
                    }
                    Text(
                        text = "Volume",
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = {
                        onUpdateVolume(volume)
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Check, contentDescription = "Done", tint = colors.accent)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Level", color = colors.textPrimary, fontSize = 14.sp)
                    Text("${(volume * 100f).roundToInt()}%", color = colors.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Slider(
                    value = volume,
                    onValueChange = {
                        volume = it
                        onUpdateVolume(it)
                    },
                    valueRange = 0.0f..2.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accent,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.border
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0%", color = colors.textSecondary, fontSize = 11.sp)
                    Text("100%", color = colors.textSecondary, fontSize = 11.sp)
                    Text("200%", color = colors.textSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
internal fun AudioSpeedSheet(
    item: OverlayItem,
    onUpdateSpeed: (speed: Float) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = FlowCutTheme.colors
    var speed by remember(item.id) { mutableFloatStateOf(item.speed) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {},
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = colors.surface0,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(0.5.dp, colors.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.textPrimary)
                    }
                    Text(
                        text = "Speed",
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = {
                        onUpdateSpeed(speed)
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Check, contentDescription = "Done", tint = colors.accent)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Speed", color = colors.textPrimary, fontSize = 14.sp)
                    Text(String.format(Locale.US, "%.1fx", speed), color = colors.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Slider(
                    value = speed,
                    onValueChange = {
                        speed = it
                        onUpdateSpeed(it)
                    },
                    valueRange = 0.2f..4.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accent,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.border
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0.2x", color = colors.textSecondary, fontSize = 11.sp)
                    Text("1.0x", color = colors.textSecondary, fontSize = 11.sp)
                    Text("2.0x", color = colors.textSecondary, fontSize = 11.sp)
                    Text("4.0x", color = colors.textSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

internal fun generateSynthesizedAudioFile(
    context: Context,
    id: String,
    genreOrType: String,
    title: String,
    durationSec: Float
): String {
    val dir = File(context.cacheDir, "synth_audio").apply { mkdirs() }
    val file = File(dir, "${id}.wav")
    if (file.exists() && file.length() > 1000L) {
        return file.absolutePath
    }

    val sampleRate = 44100
    val numSamples = (sampleRate * durationSec).toInt()
    val bytesPerSample = 2 // 16-bit
    val numChannels = 1
    val dataSize = numSamples * numChannels * bytesPerSample

    val output = ByteArrayOutputStream(44 + dataSize)
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray(Charsets.US_ASCII))
    header.putInt(36 + dataSize)
    header.put("WAVE".toByteArray(Charsets.US_ASCII))
    header.put("fmt ".toByteArray(Charsets.US_ASCII))
    header.putInt(16) // Subchunk1Size
    header.putShort(1.toShort()) // PCM format
    header.putShort(numChannels.toShort())
    header.putInt(sampleRate)
    header.putInt(sampleRate * numChannels * bytesPerSample)
    header.putShort((numChannels * bytesPerSample).toShort())
    header.putShort(16.toShort())
    header.put("data".toByteArray(Charsets.US_ASCII))
    header.putInt(dataSize)
    output.write(header.array())

    val pcm = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
    val baseFreq = when (id) {
        "pop" -> 440.0
        "chill" -> 261.63
        "cinematic" -> 130.81
        "trap" -> 146.83
        "acoustic" -> 329.63
        "synthwave" -> 220.0
        "whoosh" -> 100.0
        "pop_fx" -> 800.0
        "ding" -> 880.0
        "applause" -> 500.0
        "swoosh" -> 600.0
        "glitch" -> 350.0
        "bassdrop" -> 150.0
        "beep" -> 1000.0
        else -> 440.0
    }

    var phase = 0.0
    val random = Random(42)

    for (i in 0 until numSamples) {
        val t = i.toDouble() / sampleRate
        val progress = i.toDouble() / numSamples

        val sampleVal: Double = when (id) {
            "whoosh" -> {
                val noise = (random.nextDouble() * 2.0 - 1.0)
                val env = Math.sin(progress * Math.PI)
                noise * env * 0.7
            }
            "applause" -> {
                val noise = (random.nextDouble() * 2.0 - 1.0)
                val env = (1.0 - progress * 0.7).coerceIn(0.0, 1.0)
                noise * env * 0.5
            }
            "ding" -> {
                val env = Math.exp(-progress * 4.0)
                Math.sin(2.0 * Math.PI * 880.0 * t) * env * 0.8
            }
            "pop_fx" -> {
                val freq = 800.0 * (1.0 - progress) + 150.0
                phase += 2.0 * Math.PI * freq / sampleRate
                val env = (1.0 - progress).coerceIn(0.0, 1.0)
                Math.sin(phase) * env * 0.8
            }
            "beep" -> {
                Math.sin(2.0 * Math.PI * 1000.0 * t) * 0.6
            }
            "bassdrop" -> {
                val freq = (150.0 * Math.exp(-progress * 3.0)).coerceAtLeast(30.0)
                phase += 2.0 * Math.PI * freq / sampleRate
                val env = (1.0 - progress * 0.5).coerceIn(0.0, 1.0)
                Math.sin(phase) * env * 0.9
            }
            "glitch" -> {
                val mod = if ((i / 400) % 2 == 0) 1.5 else 0.8
                Math.sin(2.0 * Math.PI * baseFreq * mod * t) * 0.6
            }
            "swoosh" -> {
                val freq = 600.0 * (1.0 - progress) + 80.0
                phase += 2.0 * Math.PI * freq / sampleRate
                val env = Math.sin(progress * Math.PI)
                Math.sin(phase) * env * 0.7
            }
            else -> {
                val beatTime = t * 2.0
                val noteIdx = (beatTime.toInt()) % 4
                val noteMult = when (noteIdx) {
                    0 -> 1.0
                    1 -> 1.25
                    2 -> 1.5
                    3 -> 2.0
                    else -> 1.0
                }
                val curFreq = baseFreq * noteMult
                phase += 2.0 * Math.PI * curFreq / sampleRate
                val noteEnv = 1.0 - (beatTime - beatTime.toInt()) * 0.5
                Math.sin(phase) * noteEnv * 0.6
            }
        }

        val clamped = (sampleVal * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
        pcm.putShort(clamped)
    }

    output.write(pcm.array())
    file.writeBytes(output.toByteArray())
    return file.absolutePath
}
