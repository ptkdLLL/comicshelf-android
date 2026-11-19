package com.comicshelf.app.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

@Composable
private fun ReaderPageKeyEffect(keys: ReaderPageKeys?, turn: suspend (Int) -> Unit) {
    if (keys == null) return
    val requests = remember(keys) { Channel<Int>(Channel.BUFFERED) }
    val latestTurn by rememberUpdatedState(turn)
    DisposableEffect(keys, requests) {
        val detach = keys.attach { requests.trySend(it) }
        onDispose(detach)
    }
    LaunchedEffect(requests) {
        // Process clicks in order. If a touch interrupts a turn, only that turn is canceled.
        for (delta in requests) launch { latestTurn(delta) }.join()
    }
}

@Composable
internal fun ReaderPagerPageKeyEffect(keys: ReaderPageKeys?, pager: PagerState, vm: ReaderViewModel) {
    ReaderPageKeyEffect(keys) { delta ->
        // Move by a pager slot: one page, split half, landscape spread, or EPUB section.
        val target = pageTurnTarget(pager.currentPage, pager.pageCount, delta)
        if (target != null) {
            if (vm.prefs.value.pageAnim) pager.animateScrollToPage(target)
            else pager.scrollToPage(target)
        }
    }
}

@Composable
internal fun ReaderListPageKeyEffect(keys: ReaderPageKeys?, list: LazyListState, vm: ReaderViewModel) {
    ReaderPageKeyEffect(keys) { delta ->
        if (delta > 0 && !list.canScrollForward) return@ReaderPageKeyEffect
        val target = pageTurnTarget(list.firstVisibleItemIndex, vm.open.value.pageCount, delta)
        if (target != null) {
            if (vm.prefs.value.pageAnim) list.animateScrollToItem(target)
            else list.scrollToItem(target)
        }
    }
}
