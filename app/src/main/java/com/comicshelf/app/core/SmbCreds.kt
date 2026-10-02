package com.comicshelf.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * SMB 凭据存储（按 host|share 唯一）。
 *
 * 说明：凭据放在应用私有的 settings 里（明文，和本机模型文件同级），
 * 只有授予“所有文件”权限或 root 才能读到；不做二次加密以免遗忘密码
 * 导致书库打不开。密码变更时重新添加同一共享即可覆盖。
 */
object SmbCreds {

    private const val KEY = "smb_creds"

    data class Cfg(
        val host: String,   // "192.168.1.10" 或 "192.168.1.10:1445"
        val share: String,
        val user: String,
        val pass: String,
        val domain: String = "",
    )

    fun all(): List<Cfg> {
        val raw = runCatching { CsSettings.get(KEY, "[]") }.getOrDefault("[]")
        val out = ArrayList<Cfg>()
        runCatching {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Cfg(o.optString("host"), o.optString("share"),
                            o.optString("user"), o.optString("pass"),
                            o.optString("domain")))
            }
        }
        return out
    }

    private fun save(list: List<Cfg>) {
        val a = JSONArray()
        for (c in list) {
            val o = JSONObject()
            o.put("host", c.host)
            o.put("share", c.share)
            o.put("user", c.user)
            o.put("pass", c.pass)
            o.put("domain", c.domain)
            a.put(o)
        }
        CsSettings.set(KEY, a.toString())
        CsSettings.save()
    }

    /** 记住并立即注册到 native（添加 SMB 书库时调用）。 */
    fun add(c: Cfg) {
        val keep = all().filterNot { it.host.equals(c.host, true) && it.share == c.share }
        save(keep + c)
        register(c)
    }

    fun register(c: Cfg) =
        NativeBridge.registerSmb(c.host, c.share, c.user, c.pass, c.domain)

    /** 启动时调用：任何 smb:// 访问之前必须已注册。 */
    fun registerAll() {
        for (c in all()) runCatching { register(c) }
    }
}
