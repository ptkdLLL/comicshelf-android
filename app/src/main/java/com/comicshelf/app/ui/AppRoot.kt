package com.comicshelf.app.ui

import android.os.Environment
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import com.comicshelf.app.reader.ReaderScreen
import com.comicshelf.app.reader.ReaderViewModel
import com.comicshelf.app.settings.SettingsScreen
import com.comicshelf.app.shelf.ShelfScreen
import com.comicshelf.app.shelf.ShelfViewModel

sealed class Screen {
    data object Shelf : Screen()
    data class Reader(val bookId: Long, val title: String, val forceTranslate: Boolean) : Screen()
    data object Settings : Screen()
}

@Composable
fun AppRoot(onOpenAllFilesAccess: () -> Unit) {
    var screen by remember { mutableStateOf<Screen>(Screen.Shelf) }

    val shelfVm: ShelfViewModel = viewModel()
    val readerVm: ReaderViewModel = viewModel()

    // 系统返回键（手势/三键）与界面返回按钮走同一条路径（此前系统返回无人处理，
    // 阅读器/设置里按返回会直接退到桌面）。书架主屏不拦截：保持"退出应用"的默认行为。
    val closeReader: () -> Unit = {
        readerVm.closeBook()
        // 刷新阅读徽标/页数；书架滚动位置由 VM 里的 gridState 保持
        shelfVm.refreshAfterRead()
        screen = Screen.Shelf
    }
    val closeSettings: () -> Unit = { screen = Screen.Shelf }
    BackHandler(enabled = screen is Screen.Reader) { closeReader() }
    BackHandler(enabled = screen is Screen.Settings) { closeSettings() }

    val hasAllFiles = remember {
        mutableStateOf(Environment.isExternalStorageManager())
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            hasAllFiles.value = Environment.isExternalStorageManager()
            kotlinx.coroutines.delay(2000)
        }
    }

    val current = screen
    androidx.compose.animation.AnimatedContent(
        current,
        transitionSpec = {
            if (targetState is Screen.Reader) {
                (fadeIn(androidx.compose.animation.core.tween(180))) togetherWith
                    (fadeOut(androidx.compose.animation.core.tween(180)))
            } else {
                (fadeIn(androidx.compose.animation.core.tween(180))) togetherWith
                    (fadeOut(androidx.compose.animation.core.tween(180)))
            }
        },
        label = "screen",
    ) { s ->
        when (s) {
            is Screen.Shelf -> ShelfScreen(
                vm = shelfVm,
                hasAllFilesAccess = hasAllFiles.value,
                onOpenAllFilesAccess = onOpenAllFilesAccess,
                onOpenSettings = { screen = Screen.Settings },
                onOpenBook = { id, title, translate ->
                    screen = Screen.Reader(id, title, translate)
                },
            )
            is Screen.Reader -> {
                val opened by readerVm.open.collectAsState()
                androidx.compose.runtime.LaunchedEffect(s.bookId) {
                    if (opened.bookId != s.bookId) {
                        readerVm.openBook(s.bookId, s.title, s.forceTranslate)
                    }
                }
                ReaderScreen(
                    vm = readerVm,
                    onBack = closeReader,
                )
            }
            is Screen.Settings -> SettingsScreen(
                onBack = closeSettings,
            )
        }
    }
}
