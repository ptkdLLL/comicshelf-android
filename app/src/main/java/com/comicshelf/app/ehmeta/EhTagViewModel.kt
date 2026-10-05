package com.comicshelf.app.ehmeta

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comicshelf.app.core.BookCell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * tag 视图状态机（S2）：已选 tag（有序）+ picker 字典 + L1 结果窗口（手动分页）。
 *
 * 与 ShelfViewModel 完全独立（不互调、不合并——B3"纯平行视图"，零回归面），
 * 挂点由 AppRoot 持有（会话内保持筛选状态，退出重进不丢）。
 */
class EhTagViewModel : ViewModel() {

    data class PickerState(
        val ns: String? = null,               // null = 全部命名空间
        val query: String = "",
        val tags: List<EhTagQuery.TagEntry> = emptyList(),
        val loading: Boolean = false,
    )

    data class UiState(
        val dataReady: Boolean = false,
        val selected: List<EhTagQuery.TagEntry> = emptyList(),
        val picker: PickerState = PickerState(),
        val resultTotal: Int = 0,
        val computing: Boolean = false,
        val rows: List<BookCell> = emptyList(),
        val loadingMore: Boolean = false,
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private var result: EhTagQuery.Result? = null
    private var activated = false

    private val pageSize = 60

    // ---------------------------------------------------------------- 激活/字典

    fun activate() {
        val q = EhEngine.tagQuery() ?: run {
            _ui.value = _ui.value.copy(dataReady = false, error = "未载入 E-Hentai 数据包")
            return
        }
        _ui.value = _ui.value.copy(dataReady = true)
        if (activated) return
        activated = true
        loadPicker()
    }

    fun setPickerNs(ns: String?) {
        _ui.value = _ui.value.copy(picker = _ui.value.picker.copy(ns = ns))
        loadPicker()
    }

    fun setPickerQuery(q: String) {
        _ui.value = _ui.value.copy(picker = _ui.value.picker.copy(query = q))
        loadPicker()
    }

    private fun loadPicker() {
        val q = EhEngine.tagQuery() ?: return
        val p = _ui.value.picker
        _ui.value = _ui.value.copy(picker = p.copy(loading = true), error = null)
        viewModelScope.launch {
            runCatching { q.tags(p.ns, p.query) }
                .onSuccess { list ->
                    _ui.value = _ui.value.copy(
                        picker = _ui.value.picker.copy(tags = list, loading = false))
                }
                .onFailure { t ->
                    _ui.value = _ui.value.copy(
                        picker = _ui.value.picker.copy(loading = false),
                        error = "tag 字典读取失败: ${t.message}")
                }
        }
    }

    // ---------------------------------------------------------------- 选择与结果

    fun toggleTag(t: EhTagQuery.TagEntry) {
        val sel = _ui.value.selected
        val next = if (sel.any { it.rid == t.rid }) sel.filterNot { it.rid == t.rid }
                   else sel + t
        _ui.value = _ui.value.copy(selected = next)
        recompute()
    }

    fun clearTags() {
        _ui.value = _ui.value.copy(selected = emptyList())
        recompute()
    }

    /** 书籍面板跳转：按 rid 预选 */
    fun applyPreload(rid: Long?) {
        if (rid == null || rid <= 0) return
        if (_ui.value.selected.any { it.rid == rid }) return
        val q = EhEngine.tagQuery() ?: return
        viewModelScope.launch {
            val e = q.tagsByRid(longArrayOf(rid)).firstOrNull() ?: return@launch
            _ui.value = _ui.value.copy(selected = _ui.value.selected + e)
            recompute()
        }
    }

    private fun recompute() {
        val q = EhEngine.tagQuery() ?: return
        val sel = _ui.value.selected
        if (sel.isEmpty()) {
            result = null
            _ui.value = _ui.value.copy(rows = emptyList(), resultTotal = 0, computing = false)
            return
        }
        _ui.value = _ui.value.copy(computing = true, rows = emptyList(), resultTotal = 0, error = null)
        val rids = sel.map { it.rid }.toLongArray()
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            runCatching { q.result(rids, EhEngine.matchVersion) }
                .onSuccess { r ->
                    result = r
                    android.util.Log.i("EhTagUi",
                        "result rids=${rids.size} total=${r.total} ms=${System.currentTimeMillis() - t0}")
                    _ui.value = _ui.value.copy(computing = false, resultTotal = r.total)
                    loadPage(0)
                }
                .onFailure { t ->
                    _ui.value = _ui.value.copy(computing = false,
                        error = "查询失败: ${t.message}")
                }
        }
    }

    fun loadMore() {
        val r = result ?: return
        val st = _ui.value
        // 首页未落前不触发（避免 derivedStateOf 在空列表误报 needMore → 重复 off=0 查询）
        if (st.rows.isEmpty() || st.loadingMore || st.rows.size >= r.total) return
        _ui.value = st.copy(loadingMore = true)
        loadPage(st.rows.size)
    }

    private fun loadPage(offset: Int) {
        val q = EhEngine.tagQuery() ?: return
        val r = result ?: return
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            runCatching { q.page(r, offset, pageSize) }
                .onSuccess { rows ->
                    android.util.Log.i("EhTagUi",
                        "page off=$offset n=${rows.size} ms=${System.currentTimeMillis() - t0}")
                    val st = _ui.value
                    val merged = if (offset == 0) rows else st.rows + rows
                    _ui.value = st.copy(rows = merged, loadingMore = false)
                }
                .onFailure { t ->
                    _ui.value = _ui.value.copy(loadingMore = false,
                        error = "分页读取失败: ${t.message}")
                }
        }
    }

    /** 数据包变化（导入/卸载）后调用 */
    fun onDataChanged() {
        activated = false
        result = null
        _ui.value = UiState()
    }
}
