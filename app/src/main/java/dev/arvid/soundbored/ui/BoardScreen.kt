package dev.arvid.soundbored.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.arvid.soundbored.data.Board
import dev.arvid.soundbored.data.Clip
import dev.arvid.soundbored.ui.theme.ClipPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(viewModel: BoardViewModel, onAdd: () -> Unit, onEdit: (Clip) -> Unit) {
    val clips by viewModel.clips.collectAsStateWithLifecycle()
    val playing by viewModel.playing.collectAsStateWithLifecycle()
    val boards by viewModel.boards.collectAsStateWithLifecycle()
    val activeBoardId by viewModel.activeBoardId.collectAsStateWithLifecycle()
    val activeBoard = boards.firstOrNull { it.id == activeBoardId }

    var sheetClip by remember { mutableStateOf<Clip?>(null) }
    var renameClip by remember { mutableStateOf<Clip?>(null) }
    var deleteClip by remember { mutableStateOf<Clip?>(null) }
    var newBoard by remember { mutableStateOf(false) }
    var renameBoard by remember { mutableStateOf(false) }
    var deleteBoard by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    BoardPicker(
                        boards = boards,
                        activeBoard = activeBoard,
                        onSelect = viewModel::selectBoard,
                        onNewBoard = { newBoard = true },
                        onRenameBoard = { renameBoard = true },
                        onDeleteBoard = { deleteBoard = true },
                    )
                },
                actions = {
                    if (playing.isNotEmpty()) {
                        TextButton(onClick = viewModel::stopAll) { Text("Stop") }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New sound") },
            )
        },
    ) { padding ->
        if (clips.isEmpty()) {
            EmptyBoard(Modifier.padding(padding))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 128.dp),
                contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(clips, key = { it.id }) { clip ->
                    ClipButton(
                        clip = clip,
                        isPlaying = clip.id in playing,
                        onTap = { viewModel.play(clip) },
                        onLongPress = { sheetClip = clip },
                    )
                }
            }
        }
    }

    sheetClip?.let { clip ->
        ModalBottomSheet(onDismissRequest = { sheetClip = null }) {
            Column(Modifier.padding(bottom = 32.dp)) {
                Text(
                    text = clip.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                Text(
                    text = buildString {
                        append(formatDuration(clip.durationMs))
                        append(" · cut from ")
                        append("${formatTime(clip.startMs)}–${formatTime(clip.endMs)}")
                        if (clip.fadeInMs > 0L) append(" · fade in ${formatDuration(clip.fadeInMs)}")
                        if (clip.fadeOutMs > 0L) append(" · fade out ${formatDuration(clip.fadeOutMs)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                SheetAction("Edit sound") { sheetClip = null; onEdit(clip) }
                SheetAction("Rename") { renameClip = clip; sheetClip = null }
                SheetAction("Delete") { deleteClip = clip; sheetClip = null }
            }
        }
    }

    renameClip?.let { clip ->
        var text by remember(clip.id) { mutableStateOf(clip.name) }
        AlertDialog(
            onDismissRequest = { renameClip = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Button label") },
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (text.isNotBlank()) viewModel.rename(clip, text)
                    renameClip = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameClip = null }) { Text("Cancel") } },
        )
    }

    deleteClip?.let { clip ->
        AlertDialog(
            onDismissRequest = { deleteClip = null },
            title = { Text("Delete \"${clip.name}\"?") },
            confirmButton = {
                Button(onClick = { viewModel.delete(clip); deleteClip = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteClip = null }) { Text("Cancel") } },
        )
    }

    if (newBoard) {
        BoardNameDialog(
            title = "New board",
            initial = "",
            confirm = "Create",
            onDismiss = { newBoard = false },
            onConfirm = { viewModel.addBoard(it); newBoard = false },
        )
    }

    if (renameBoard && activeBoard != null) {
        BoardNameDialog(
            title = "Rename board",
            initial = activeBoard.name,
            confirm = "Save",
            onDismiss = { renameBoard = false },
            onConfirm = { viewModel.renameBoard(activeBoard.id, it); renameBoard = false },
        )
    }

    if (deleteBoard && activeBoard != null) {
        AlertDialog(
            onDismissRequest = { deleteBoard = false },
            title = { Text("Delete \"${activeBoard.name}\"?") },
            text = {
                if (clips.isNotEmpty()) {
                    Text("Its ${clips.size} sound${if (clips.size == 1) "" else "s"} go too.")
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.deleteBoard(activeBoard.id); deleteBoard = false }) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { deleteBoard = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BoardPicker(
    boards: List<Board>,
    activeBoard: Board?,
    onSelect: (String) -> Unit,
    onNewBoard: () -> Unit,
    onRenameBoard: () -> Unit,
    onDeleteBoard: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(
                text = activeBoard?.name ?: "Soundbored",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Switch board")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            boards.forEach { board ->
                DropdownMenuItem(
                    text = { Text(board.name) },
                    onClick = { onSelect(board.id); expanded = false },
                    leadingIcon = {
                        if (board.id == activeBoard?.id) {
                            Icon(Icons.Default.Check, contentDescription = "Current board")
                        }
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("New board…") },
                onClick = { expanded = false; onNewBoard() },
            )
            DropdownMenuItem(
                text = { Text("Rename this board…") },
                onClick = { expanded = false; onRenameBoard() },
            )
            if (boards.size > 1) {
                DropdownMenuItem(
                    text = { Text("Delete this board") },
                    onClick = { expanded = false; onDeleteBoard() },
                )
            }
        }
    }
}

@Composable
private fun BoardNameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Board name") },
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipButton(
    clip: Clip,
    isPlaying: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val color = ClipPalette[clip.colorIndex.mod(ClipPalette.size)]
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(color)
            .then(
                if (isPlaying) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, shape)
                } else {
                    Modifier
                }
            )
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = clip.name,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 3,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = formatDuration(clip.durationMs),
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
    }
}

@Composable
private fun EmptyBoard(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "No sounds on this board",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}
