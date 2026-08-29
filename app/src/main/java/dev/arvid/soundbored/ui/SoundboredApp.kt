package dev.arvid.soundbored.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SoundboredApp(sharedUrl: String?, onSharedUrlHandled: () -> Unit) {
    val boardViewModel: BoardViewModel = viewModel()
    val addViewModel: AddClipViewModel = viewModel()
    var showAdd by remember { mutableStateOf(false) }

    LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrBlank()) {
            addViewModel.onUrlChange(sharedUrl)
            showAdd = true
            addViewModel.load()
            onSharedUrlHandled()
        }
    }

    if (showAdd) {
        BackHandler {
            addViewModel.discard()
            showAdd = false
        }
        AddClipScreen(
            viewModel = addViewModel,
            onClose = {
                addViewModel.discard()
                showAdd = false
            },
            onSaved = {
                addViewModel.discard()
                showAdd = false
            },
        )
    } else {
        BoardScreen(
            viewModel = boardViewModel,
            onAdd = { showAdd = true },
            onEdit = { clip ->
                addViewModel.loadForEdit(clip)
                showAdd = true
            },
        )
    }
}
