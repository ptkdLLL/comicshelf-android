package com.comicshelf.app.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comicshelf.app.core.EpubBookSource
import com.comicshelf.app.core.EpubBookSource.Block
import com.comicshelf.app.core.EpubBookSource.Page

/**
 * One EPUB spine entry per navigation step. Horizontal reading pages between
 * sections while allowing long chapters to scroll vertically within a section.
 */
@Composable
internal fun EpubReaderScreen(
    vm: ReaderViewModel,
    open: ReaderOpenState,
    mode: ReaderMode,
    prefs: ReaderPrefs,
) {
    val textColor = if (prefs.bg == 1) Color(0xFF202020) else Color(0xFFE8E8E8)
    if (!mode.vertical) {
        EpubHorizontalReader(vm, open, mode.rtl, textColor)
        return
    }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = open.page)

    // Same double-guard as ReaderScrollView: settle writes, external jumps scroll.
    LaunchedEffect(list.firstVisibleItemIndex, list.isScrollInProgress) {
        if (!list.isScrollInProgress) {
            val v = list.firstVisibleItemIndex
            if (v != vm.open.value.page) vm.goto(v)
        }
    }
    LaunchedEffect(open.page) {
        if (open.page != list.firstVisibleItemIndex && !list.isScrollInProgress) {
            if (vm.prefs.value.pageAnim) list.animateScrollToItem(open.page)
            else list.scrollToItem(open.page)
        }
    }

    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 96.dp, bottom = 88.dp),
    ) {
        items(open.pageCount, key = { it }) { index ->
            EpubSpineItemView(vm, open.bookId, index, textColor)
        }
    }
}

@Composable
private fun EpubHorizontalReader(
    vm: ReaderViewModel,
    open: ReaderOpenState,
    rtl: Boolean,
    textColor: Color,
) {
    val pager = rememberPagerState(initialPage = open.page) { open.pageCount }
    LaunchedEffect(pager.currentPage, pager.isScrollInProgress) {
        if (!pager.isScrollInProgress && pager.currentPage != vm.open.value.page) {
            vm.goto(pager.currentPage)
        }
    }
    LaunchedEffect(open.page) {
        if (open.page != pager.currentPage && !pager.isScrollInProgress) {
            if (vm.prefs.value.pageAnim) pager.animateScrollToPage(open.page)
            else pager.scrollToPage(open.page)
        }
    }
    val contentDirection = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        HorizontalPager(state = pager, reverseLayout = rtl, modifier = Modifier.fillMaxSize()) { index ->
            CompositionLocalProvider(LocalLayoutDirection provides contentDirection) {
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 96.dp, bottom = 88.dp),
                ) {
                    EpubSpineItemView(vm, open.bookId, index, textColor)
                }
            }
        }
    }
}

@Composable
private fun EpubSpineItemView(
    vm: ReaderViewModel,
    bookId: Long,
    index: Int,
    textColor: Color,
) {
    val page by produceState<Page?>(null, bookId, index) {
        value = EpubBookSource.loadPage(bookId, index)
    }
    val p = page
    if (p == null) {
        Box(
            Modifier.fillMaxWidth().height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 1.5.dp)
        }
        return
    }

    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = if (p.imageOnly) 0.dp else 20.dp, vertical = 10.dp),
    ) {
        p.blocks.forEach { block ->
            when (block) {
                is Block.Text -> {
                    val heading = block.heading > 0
                    Text(
                        block.text,
                        color = textColor,
                        fontSize = if (heading) 22.sp else 18.sp,
                        lineHeight = if (heading) 34.sp else 30.sp,
                        fontWeight = if (heading) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth()
                            .padding(vertical = if (heading) 10.dp else 5.dp),
                    )
                }
                is Block.Image -> {
                    val bmp by produceState<Bitmap?>(null, bookId, block.href) {
                        value = EpubBookSource.loadImage(bookId, block.href, 16L * 1024 * 1024)
                    }
                    val b = bmp
                    if (b != null) {
                        Image(
                            b.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth()
                                .aspectRatio(b.width.toFloat() / b.height.coerceAtLeast(1))
                                .padding(vertical = 6.dp),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().height(160.dp))
                    }
                }
            }
        }
    }
}
