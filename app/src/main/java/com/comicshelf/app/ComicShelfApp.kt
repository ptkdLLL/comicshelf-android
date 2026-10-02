package com.comicshelf.app

import android.app.Application
import com.comicshelf.app.core.NativeBridge
import com.comicshelf.app.core.SmbCreds
import kotlinx.coroutines.runBlocking
import java.io.File

class ComicShelfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.comicshelf.app.util.initAppContext(this)
        val data = File(filesDir, "data").apply { mkdirs() }
        val tmp = cacheDir.resolve("rar").apply { mkdirs() }
        runBlocking { NativeBridge.start(data.absolutePath, tmp.absolutePath) }
        // SMB 书库：先把已保存的凭据注册进 native，之后 smb:// 路径才能解析。
        runCatching { SmbCreds.registerAll() }
        // 实时同步（SMB2 CHANGE_NOTIFY）：NAS 上的增删改会近实时反映到书库。
        Thread {
            runCatching {
                val msg = NativeBridge.autoSync(true)
                android.util.Log.i("ComicShelf", "autosync: $msg")
            }
        }.start()
    }
}
