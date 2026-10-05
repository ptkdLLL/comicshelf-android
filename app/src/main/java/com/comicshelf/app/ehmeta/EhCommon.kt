package com.comicshelf.app.ehmeta

/** 后台作业被用户取消（导入/索引/匹配共用） */
class EhCancelledException : Exception("cancelled")

/** 引擎状态（S1 无 UI：由调试驱动/S3 设置页消费） */
sealed class EhPhase {
    /** 未载入数据包（默认态，零资源） */
    data object NotLoaded : EhPhase()
    /** 导入/建索引中 */
    data class Importing(val stage: String, val pct: Int) : EhPhase()
    /** 就绪（ehmeta.db + keys.bin 齐备） */
    data class Ready(val keys: Long) : EhPhase()
    /** 匹配作业运行中 */
    data class Matching(val done: Long, val total: Long) : EhPhase()
    /** 损坏/失败（附原因文本） */
    data class Broken(val reason: String) : EhPhase()
}

/** 匹配统计 */
data class EhMatchStats(
    val total: Long,
    val matched: Long,
    val newlyMatched: Long,
    val elapsedMs: Long,
    val breakdown: EhMatcher.Breakdown? = null,
)

/** EH 命名空间显示序与中文标签（与 EH 页面一致；查询层与 UI 共用） */
val EH_NS_ORDER: List<Pair<String, String>> = listOf(
    "language" to "语言", "parody" to "原作", "character" to "角色", "group" to "社团",
    "artist" to "画师", "male" to "男性", "female" to "女性", "mixed" to "混合",
    "other" to "其他", "cosplayer" to "Cosplay", "location" to "地点",
    "temp" to "未分类", "reclass" to "重分类", "rest" to "杂项",
)

fun ehNsLabel(ns: String): String = EH_NS_ORDER.firstOrNull { it.first == ns }?.second ?: ns
