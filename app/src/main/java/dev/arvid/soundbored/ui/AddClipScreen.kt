package dev.arvid.soundbored.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddClipScreen(
    viewModel: AddClipViewModel,
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.editing != null) "Edit sound" else "New sound") },
                navigationIcon = {
                    val onUrlStep = viewModel.stage == AddClipViewModel.Stage.Input &&
                        viewModel.editing == null
                    IconButton(onClick = { if (onUrlStep) viewModel.backToChoose() else onClose() }) {
                        Icon(
                            imageVector = if (onUrlStep) {
                                Icons.AutoMirrored.Filled.ArrowBack
                            } else {
                                Icons.Default.Close
                            },
                            contentDescription = if (onUrlStep) "Back" else "Close",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // The app draws edge to edge, so the keyboard has to shrink the scroll
                // viewport itself — otherwise it covers whichever field is being typed in.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            val pickFile = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> uri?.let { viewModel.importLocal(it) } }

            when (val stage = viewModel.stage) {
                AddClipViewModel.Stage.Choose -> SourceChooser(viewModel) {
                    pickFile.launch(arrayOf("audio/*", "application/ogg"))
                }
                AddClipViewModel.Stage.Input -> UrlInput(viewModel)
                AddClipViewModel.Stage.Importing -> Busy("Reading the file…", null, null)
                AddClipViewModel.Stage.Resolving -> Busy("Reading the video…", null, viewModel::cancelLoad)
                is AddClipViewModel.Stage.Fetching -> Busy(
                    label = "Downloading audio${
                        if (stage.total > 0) " · ${formatBytes(stage.bytes)} of ${formatBytes(stage.total)}" else ""
                    }",
                    progress = if (stage.total > 0) (stage.bytes.toFloat() / stage.total) else null,
                    onCancel = viewModel::cancelLoad,
                )
                is AddClipViewModel.Stage.Ready -> Editor(viewModel, stage.audio, onSaved)
                AddClipViewModel.Stage.Saving -> Busy("Cutting the clip…", null, null)
            }
        }
    }
}

@Composable
private fun SourceChooser(viewModel: AddClipViewModel, onPickFile: () -> Unit) {
    Text(
        text = "Where is the sound from?",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 16.dp),
    )
    SourceOption("YouTube link", Icons.Default.PlayArrow, viewModel::chooseYoutube)
    SourceOption("Audio file on this device", Icons.Default.Add, onPickFile)
    viewModel.error?.let { message ->
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun SourceOption(title: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

@Composable
private fun UrlInput(viewModel: AddClipViewModel) {
    OutlinedTextField(
        value = viewModel.url,
        onValueChange = viewModel::onUrlChange,
        label = { Text("YouTube link") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    viewModel.error?.let { message ->
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    Button(
        onClick = viewModel::load,
        enabled = viewModel.url.isNotBlank(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) { Text("Load audio") }
}

@Composable
private fun Busy(label: String, progress: Float?, onCancel: (() -> Unit)?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
    ) {
        if (progress == null) {
            CircularProgressIndicator()
        } else {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        if (onCancel != null) {
            TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun Editor(viewModel: AddClipViewModel, audio: LoadedAudio, onSaved: () -> Unit) {
    Text(audio.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
    Text(
        text = listOf(audio.subtitle, formatDuration(audio.durationMs))
            .filter { it.isNotBlank() }
            .joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
    )

    WaveformSelector(
        peaks = viewModel.peaks,
        durationMs = audio.durationMs,
        startMs = viewModel.startMs,
        endMs = viewModel.endMs,
        playheadMs = viewModel.playheadMs,
        onRangeChange = viewModel::updateRange,
        onMove = viewModel::moveRange,
        fadeInMs = viewModel.fadeInMs,
        fadeOutMs = viewModel.fadeOutMs,
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
    )

    if (viewModel.waveformProgress < 1f) {
        LinearProgressIndicator(
            progress = { viewModel.waveformProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
        )
    }

    RangeSlider(
        value = viewModel.startMs.toFloat()..viewModel.endMs.toFloat(),
        onValueChange = { range ->
            viewModel.updateRange(range.start.toLong(), range.endInclusive.toLong())
        },
        valueRange = 0f..audio.durationMs.toFloat(),
        modifier = Modifier.fillMaxWidth(),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(formatTime(viewModel.startMs), style = MaterialTheme.typography.labelLarge)
        Text(
            text = formatDuration(viewModel.endMs - viewModel.startMs),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(formatTime(viewModel.endMs), style = MaterialTheme.typography.labelLarge)
    }

    // A five-second pick inside a five-minute song is a couple of pixels wide up there,
    // so the same widget is shown again zoomed to the selection plus a little air.
    val length = viewModel.endMs - viewModel.startMs
    val padding = maxOf(750L, length / 2)
    val desired = length + padding * 2
    val windowStart = when {
        desired >= audio.durationMs -> 0L
        else -> (viewModel.startMs - padding).coerceIn(0L, audio.durationMs - desired)
    }
    val windowEnd = if (desired >= audio.durationMs) audio.durationMs else windowStart + desired

    Text(
        text = "Fine tune",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
    WaveformSelector(
        peaks = viewModel.peaks,
        durationMs = audio.durationMs,
        startMs = viewModel.startMs,
        endMs = viewModel.endMs,
        playheadMs = viewModel.playheadMs,
        onRangeChange = viewModel::updateRange,
        onMove = viewModel::moveRange,
        windowStartMs = windowStart,
        windowEndMs = windowEnd,
        fadeInMs = viewModel.fadeInMs,
        fadeOutMs = viewModel.fadeOutMs,
        onFadeInChange = viewModel::setFadeIn,
        onFadeOutChange = viewModel::setFadeOut,
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp),
    )

    NudgeRow("Start", viewModel::nudgeStart)
    NudgeRow("End", viewModel::nudgeEnd)
    FadeRow("Fade in", viewModel.fadeInMs, viewModel::nudgeFadeIn) { viewModel.setFadeIn(0L) }
    FadeRow("Fade out", viewModel.fadeOutMs, viewModel::nudgeFadeOut) { viewModel.setFadeOut(0L) }

    OutlinedButton(
        onClick = viewModel::togglePreview,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null)
        Text(
            text = if (viewModel.isPreviewing) "Stop preview" else "Play selection",
            modifier = Modifier.padding(start = 8.dp),
        )
    }

    OutlinedTextField(
        value = viewModel.name,
        onValueChange = viewModel::onNameChange,
        label = { Text("Button label") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    )

    viewModel.error?.let { message ->
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    Button(
        onClick = { viewModel.save(onSaved) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 24.dp),
    ) { Text(if (viewModel.editing != null) "Save changes" else "Add to board") }
}

@Composable
private fun FadeRow(label: String, valueMs: Long, onNudge: (Long) -> Unit, onClear: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            text = if (valueMs <= 0L) "none" else formatDuration(valueMs),
            style = MaterialTheme.typography.labelLarge,
            color = if (valueMs <= 0L) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.padding(start = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        listOf(-500L to "−.5", -100L to "−.1", 100L to "+.1", 500L to "+.5").forEach { (delta, text) ->
            TextButton(
                onClick = { onNudge(delta) },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text(text) }
        }
        TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("off")
        }
    }
}

@Composable
private fun NudgeRow(label: String, onNudge: (Long) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Box(modifier = Modifier.padding(end = 4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
        listOf(-1000L to "-1s", -100L to "-.1", 100L to "+.1", 1000L to "+1s").forEach { (delta, text) ->
            TextButton(onClick = { onNudge(delta) }) { Text(text) }
        }
    }
}
