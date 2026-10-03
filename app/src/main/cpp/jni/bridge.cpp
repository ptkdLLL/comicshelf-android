// JNI bridge between the Kotlin/Compose app and the proven C++ core.
//
// Threading contract (mirrors the Windows app's UI-thread confinement):
//  * every entry point takes g_bridge_mtx, so Kotlin may call from any
//    dispatcher without racing LibraryManager state;
//  * page reads / OCR long jobs run on the core ThreadPool, never blocking
//    the caller beyond the mutex hold time.
// Hot paths (paging window, cover polling, page bytes) exchange packed
// primitive arrays instead of JSON to keep the per-call cost tiny.
#include "core/library.h"
#include "core/auto_sync.h"
#include "translate/llm_local.h"
#include "util/path_util.h"
#include "core/scanner.h"
#include "vfs/vfs.h"
#include "core/settings.h"
#include "image/image_util.h"
#include "translate/translate_engine.h"
#include "util/crash_handler.h"
#include "util/json_min.h"
#include "util/logger.h"

#include <android/bitmap.h>
#include <jni.h>

#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

using namespace cs;

namespace {

std::mutex g_bridge_mtx;

struct Core {
    std::unique_ptr<ThreadPool> pool;
    std::unique_ptr<Settings> settings;
    std::unique_ptr<LibraryManager> lib;
    std::unique_ptr<AutoSync> autosync;
    std::unique_ptr<translate::TranslateEngine> translator;
    bool started = false;
};
Core g_core;

struct ReaderSession {
    Book book;
    std::shared_ptr<IArchive> arch;
    std::mutex mtx;
};
std::unordered_map<int64_t, std::shared_ptr<ReaderSession>> g_readers;

// ---------------------------------------------------------------- helpers

std::string to_std(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* p = env->GetStringUTFChars(s, nullptr);
    std::string out(p ? p : "");
    env->ReleaseStringUTFChars(s, p);
    return out;
}

// Fully correct UTF-8 (NewStringUTF would use modified UTF-8 and break on
// supplementary characters, which do appear in file names).
jstring from_std(JNIEnv* env, const std::string& utf8) {
    if (utf8.empty()) return env->NewStringUTF("");
    jbyteArray bytes = env->NewByteArray((jsize)utf8.size());
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, (jsize)utf8.size(), (const jbyte*)utf8.data());
    jclass cls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "([BLjava/nio/charset/Charset;)V");
    jclass charsetCls = env->FindClass("java/nio/charset/StandardCharsets");
    jfieldID utf8Field = env->GetStaticFieldID(charsetCls, "UTF_8", "Ljava/nio/charset/Charset;");
    jobject utf8Charset = env->GetStaticObjectField(charsetCls, utf8Field);
    jobject str = env->NewObject(cls, ctor, bytes, utf8Charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(utf8Charset);
    env->DeleteLocalRef(charsetCls);
    env->DeleteLocalRef(cls);
    return (jstring)str;
}

jlongArray to_long_array(JNIEnv* env, const std::vector<int64_t>& v) {
    jlongArray a = env->NewLongArray((jsize)v.size());
    if (!a) return nullptr;
    env->SetLongArrayRegion(a, 0, (jsize)v.size(), (const jlong*)v.data());
    return a;
}

jintArray to_int_array(JNIEnv* env, const std::vector<int>& v) {
    jintArray a = env->NewIntArray((jsize)v.size());
    if (!a) return nullptr;
    env->SetIntArrayRegion(a, 0, (jsize)v.size(), (const jint*)v.data());
    return a;
}

// {w, h, rgba bytes} for decoded images.
jobject image_bundle(JNIEnv* env, int w, int h, const uint8_t* rgba, size_t n) {
    if (w <= 0 || h <= 0 || n < (size_t)w * h * 4) return nullptr;
    jintArray dims = env->NewIntArray(2);
    jint dv[2] = {w, h};
    env->SetIntArrayRegion(dims, 0, 2, dv);
    jbyteArray px = env->NewByteArray((jsize)n);
    env->SetByteArrayRegion(px, 0, (jsize)n, (const jbyte*)rgba);
    jobjectArray arr = env->NewObjectArray(2, env->FindClass("java/lang/Object"), nullptr);
    env->SetObjectArrayElement(arr, 0, dims);
    env->SetObjectArrayElement(arr, 1, px);
    return arr;
}

Book find_book(int64_t id) {
    Book b;
    if (g_core.lib) g_core.lib->db().get_book(id, b);
    return b;
}

std::shared_ptr<ReaderSession> find_session(int64_t book_id) {
    auto it = g_readers.find(book_id);
    return it == g_readers.end() ? nullptr : it->second;
}

SortKey sort_from(int s) {
    switch (s) {
        case 1: return SortKey::Title;
        case 2: return SortKey::Path;
        case 3: return SortKey::Size;
        case 4: return SortKey::Mtime;
        case 5: return SortKey::Pages;
        default: return SortKey::Added;
    }
}

std::string json_dirs(const std::vector<DirRow>& rows) {
    json::Value arr = json::Value::make_array();
    for (const auto& d : rows) {
        json::Value o = json::Value::make_object();
        o.set("id", d.id);
        o.set("name", d.name);
        o.set("rel", d.rel);
        o.set("parent", d.parent_rel);
        o.set("count", d.book_count);
        o.set("total", d.total_count);
        o.set("depth", (int64_t)d.depth);
        arr.push_back(o);
    }
    return arr.dump();
}

std::string json_bookmarks(const std::vector<BookmarkRow>& rows) {
    json::Value arr = json::Value::make_array();
    for (const auto& b : rows) {
        json::Value o = json::Value::make_object();
        o.set("id", b.id);
        o.set("bookId", b.book_id);
        o.set("title", b.book_title);
        o.set("page", (int64_t)b.page);
        o.set("label", b.label);
        arr.push_back(o);
    }
    return arr.dump();
}

std::string json_strings(const std::vector<std::string>& v) {
    json::Value arr = json::Value::make_array();
    for (const auto& s : v) arr.push_back(s);
    return arr.dump();
}

} // namespace

extern "C" {

// ---------------------------------------------------------------- lifecycle

JNIEXPORT jboolean JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeStart(JNIEnv* env, jobject, jstring data_dir,
                                                       jstring tmp_dir) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (g_core.started) return JNI_TRUE;
    const std::string data = to_std(env, data_dir);
    const std::string tmp = to_std(env, tmp_dir);
    if (!tmp.empty()) {
        ::setenv("CS_TMPDIR", tmp.c_str(), 1); // unrar extraction scratch space
        ::setenv("TMPDIR", tmp.c_str(), 1);
    }

    log_open(data + "/comicshelf.log");
    log_info("ComicShelf android starting");
    install_crash_handler(data + "/crashes");

    g_core.settings = std::make_unique<Settings>();
    g_core.settings->load(data + "/config.ini");

    unsigned hw = std::thread::hardware_concurrency();
    g_core.pool = std::make_unique<ThreadPool>(std::max(2u, std::min(6u, hw)));

    g_core.lib = std::make_unique<LibraryManager>(*g_core.settings, *g_core.pool, data);
    std::string err;
    if (!g_core.lib->init(&err)) {
        log_error("library init failed: " + err);
        g_core.lib.reset();
        return JNI_FALSE;
    }

    g_core.translator = std::make_unique<translate::TranslateEngine>(g_core.lib->db());
    g_core.translator->configure(*g_core.settings);
    g_core.started = true;
    log_info("ComicShelf android ready (threads=" + std::to_string(g_core.pool->size()) + ")");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeStop(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.started) return;
    g_readers.clear();
    g_core.translator.reset();
    if (g_core.autosync) { g_core.autosync->stop(); g_core.autosync.reset(); }
    g_core.lib.reset(); // joins the scanner + drains the pool
    g_core.pool.reset();
    g_core.settings.reset();
    g_core.started = false;
    log_close();
}

// ---------------------------------------------------------------- settings

JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeSettingsGet(JNIEnv* env, jobject, jstring k,
                                                            jstring def) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.settings) return def;
    return from_std(env, g_core.settings->get(to_std(env, k), to_std(env, def)));
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeSettingsSet(JNIEnv* env, jobject, jstring k,
                                                            jstring v) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (g_core.settings) g_core.settings->set(to_std(env, k), to_std(env, v));
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeSettingsSave(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (g_core.settings) g_core.settings->save();
}

// ---------------------------------------------------------------- libraries

JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeLibraries(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return env->NewStringUTF("[]");
    json::Value arr = json::Value::make_array();
    for (const auto& l : g_core.lib->libraries()) {
        json::Value o = json::Value::make_object();
        o.set("id", l.id);
        o.set("root", l.root);
        o.set("name", l.name);
        o.set("count", l.book_count);
        o.set("lastScan", l.last_scan);
        arr.push_back(o);
    }
    return from_std(env, arr.dump());
}

JNIEXPORT jlong JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeAddLibrary(JNIEnv* env, jobject, jstring root,
                                                            jboolean scan) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return 0;
    return g_core.lib->add_library_root(to_std(env, root), scan == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeRemoveLibrary(JNIEnv*, jobject, jlong id) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (g_core.lib) g_core.lib->remove_library(id);
}

JNIEXPORT jboolean JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeRenameLibrary(JNIEnv* env, jobject, jlong id,
                                                              jstring name) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return JNI_FALSE;
    return g_core.lib->update_library_name(id, to_std(env, name)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeLibraryStats(JNIEnv* env, jobject, jlong id) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return nullptr;
    int64_t books = 0, folders = 0;
    int depth = 0;
    if (!g_core.lib->library_stats(id, books, folders, depth)) return nullptr;
    std::vector<int64_t> v{books, folders, depth};
    return to_long_array(env, v);
}

// ---------------------------------------------------------------- scanning

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeScanStart(JNIEnv*, jobject, jlong lib_id) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (g_core.lib) g_core.lib->start_scan(lib_id);
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeScanCancel(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (g_core.lib) g_core.lib->cancel_scan();
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeScanPause(JNIEnv*, jobject, jboolean p) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (g_core.lib) g_core.lib->pause_scan(p == JNI_TRUE);
}

JNIEXPORT jlongArray JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeScanProgress(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return nullptr;
    const ScanProgress& p = g_core.lib->progress();
    std::vector<int64_t> v{p.running.load() ? 1 : 0, p.cancel.load() ? 1 : 0,
                           p.paused.load() ? 1 : 0, p.lib_id.load(), p.entries_seen.load(),
                           p.added.load(), p.updated.load(), p.removed.load(),
                           p.dirs_seen.load(), p.serial.load()};
    return to_long_array(env, v);
}

// SMB 凭据注册/连通性测试/子树刷新

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeRegisterSmb(JNIEnv* env, jobject, jstring host,
                                                            jstring share, jstring user,
                                                            jstring pass, jstring domain) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    vfs::SmbConfig cfg;
    cfg.host = to_std(env, host);
    cfg.share = to_std(env, share);
    cfg.user = to_std(env, user);
    cfg.password = to_std(env, pass);
    cfg.domain = to_std(env, domain);
    vfs::register_smb_credentials(cfg);
}

// 返回 "" 表示连通；否则是错误文本（给“添加 SMB 书库”对话框即时校验用）
JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeSmbProbe(JNIEnv* env, jobject, jstring host,
                                                         jstring share, jstring user,
                                                         jstring pass, jstring domain) {
    vfs::SmbConfig cfg;
    cfg.host = to_std(env, host);
    cfg.share = to_std(env, share);
    cfg.user = to_std(env, user);
    cfg.password = to_std(env, pass);
    cfg.domain = to_std(env, domain);
    std::string err;
    auto v = vfs::make_smb_vfs(cfg, &err);
    (void)v;
    return from_std(env, err);
}

// 实时同步（SMB2 CHANGE_NOTIFY）：为所有 SMB 书库建立/停止监视
JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeAutoSync(JNIEnv* env, jobject, jboolean on) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return from_std(env, "未初始化");
    if (on == JNI_TRUE) {
        if (!g_core.autosync) g_core.autosync = std::make_unique<AutoSync>(*g_core.lib);
        g_core.autosync->start();
        return from_std(env, g_core.autosync->supported()
                                 ? ("实时同步已启用（" + std::to_string(g_core.autosync->watchers()) + " 个书库）")
                                 : "服务器不支持实时变更通知（将回退为手动扫描）");
    }
    if (g_core.autosync) { g_core.autosync->stop(); g_core.autosync.reset(); }
    return from_std(env, "实时同步已停止");
}

// 实时同步状态文本（设置页显示 / 重新检测）
JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeAutoSyncStatus(JNIEnv* env, jobject,
                                                              jboolean recheck) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return from_std(env, "未初始化");
    if (recheck == JNI_TRUE) {
        if (!g_core.autosync) g_core.autosync = std::make_unique<AutoSync>(*g_core.lib);
        g_core.autosync->start();
    } else if (!g_core.autosync) {
        return from_std(env, "未启用");
    }
    const int n = g_core.autosync->alive_count();
    if (n > 0) return from_std(env, "实时同步中（" + std::to_string(n) + " 个书库）");
    if (g_core.autosync->watchers() > 0)
        return from_std(env, "服务器不支持实时变更通知：请用“重扫”或在目录上点刷新");
    return from_std(env, "没有可监视的 SMB 书库");
}

// 列出服务器的共享名（给“添加 SMB 书库”向导）
JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeSmbShares(JNIEnv* env, jobject, jstring host,
                                                          jstring user, jstring pass,
                                                          jstring domain) {
    std::vector<std::string> names;
    std::string err;
    vfs::smb_list_shares(to_std(env, host), to_std(env, user), to_std(env, pass),
                         to_std(env, domain), &names, &err);
    json::Value o = json::Value::make_object();
    if (!err.empty()) o.set("error", err);
    json::Value a = json::Value::make_array();
    for (auto& n : names) a.push_back(json::Value(n));
    o.set("shares", a);
    return from_std(env, o.dump());
}

// 列出共享内某目录的子目录（供“选目录”浏览），同时给出该层的书数量
JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeSmbList(JNIEnv* env, jobject, jstring host,
                                                        jstring share, jstring sub, jstring user,
                                                        jstring pass, jstring domain) {
    vfs::SmbConfig cfg;
    cfg.host = to_std(env, host);
    cfg.share = to_std(env, share);
    cfg.user = to_std(env, user);
    cfg.password = to_std(env, pass);
    cfg.domain = to_std(env, domain);
    json::Value o = json::Value::make_object();
    std::string err;
    auto v = vfs::make_smb_vfs(cfg, &err);
    if (!v) {
        o.set("error", err);
        return from_std(env, o.dump());
    }
    std::string rel = to_std(env, sub);
    if (rel.empty()) rel = "/";
    std::vector<vfs::DirEntry> entries;
    if (!v->list(rel, &entries)) {
        o.set("error", "无法列出目录: " + rel);
        return from_std(env, o.dump());
    }
    json::Value dirs = json::Value::make_array();
    int64_t archives = 0, images = 0;
    for (const auto& e : entries) {
        if (e.st.is_dir) {
            dirs.push_back(json::Value(e.name));
        } else if (e.st.is_file) {
            const std::string dot = e.name.rfind('.') == std::string::npos
                                        ? std::string()
                                        : e.name.substr(e.name.rfind('.') + 1);
            std::string ext;
            for (char ch : dot) ext.push_back((char)std::tolower((unsigned char)ch));
            if (paths::is_archive_ext(ext)) ++archives;
            else if (paths::is_image_ext(ext)) ++images;
        }
    }
    o.set("path", rel);
    o.set("dirs", dirs);
    o.set("archives", archives);
    o.set("images", images);
    return from_std(env, o.dump());
}

// 只重扫/清理某棵子树（库根相对路径，'' = 整库）
JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeRefreshDir(JNIEnv* env, jobject, jlong lib_id,
                                                           jstring rel) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (g_core.lib) g_core.lib->refresh_subtree(lib_id, to_std(env, rel));
}

JNIEXPORT jlong JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeRemoveSubtree(JNIEnv* env, jobject, jlong lib_id,
                                                              jstring rel) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return 0;
    return g_core.lib->remove_subtree(lib_id, to_std(env, rel));
}

// ---------------------------------------------------------------- 本机翻译模型

JNIEXPORT jboolean JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeLlmLocalLoad(JNIEnv* env, jobject, jstring path,
                                                            jint threads, jint n_ctx) {
    std::string err;
    const bool ok = cs::llm::LocalLlm::instance().load(to_std(env, path), threads, n_ctx, &err);
    if (!ok) log_warn("llm_local load failed: " + err);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeLlmLocalUnload(JNIEnv*, jobject) {
    cs::llm::LocalLlm::instance().unload();
}

JNIEXPORT jboolean JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeLlmLocalLoaded(JNIEnv*, jobject) {
    return cs::llm::LocalLlm::instance().loaded() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeLlmLocalInfo(JNIEnv* env, jobject) {
    return from_std(env, cs::llm::LocalLlm::instance().info());
}

JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeLlmLocalChat(JNIEnv* env, jobject, jstring system,
                                                           jstring user, jint max_tokens,
                                                           jfloat temp, jfloat top_p,
                                                           jint top_k) {
    cs::llm::GenParams gp;
    gp.max_tokens = max_tokens > 0 ? max_tokens : 256;
    gp.temp = temp;
    gp.top_p = top_p;
    gp.top_k = top_k;
    std::string out, err;
    if (!cs::llm::LocalLlm::instance().chat(to_std(env, system), to_std(env, user), gp, &out, &err)) {
        json::Value o = json::Value::make_object();
        o.set("error", err);
        return from_std(env, o.dump());
    }
    return from_std(env, out);
}

// ---------------------------------------------------------------- queries

JNIEXPORT jlong JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeCount(JNIEnv* env, jobject, jlong lib_id,
                                                      jstring search, jstring dir_rel,
                                                      jboolean recursive, jboolean fav_only,
                                                      jint read_state) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return 0;
    return g_core.lib->count(lib_id, to_std(env, search), to_std(env, dir_rel),
                             recursive == JNI_TRUE, fav_only == JNI_TRUE, read_state);
}

// Object[6]: long[] ids, String[] titles, int[] favorite, int[] readState,
//            int[] lastPage, int[] pages. (kind/size/mtime not needed by the UI.)
JNIEXPORT jobjectArray JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativePage(JNIEnv* env, jobject, jlong lib_id,
                                                     jstring search, jint sort, jboolean desc,
                                                     jlong offset, jint limit, jstring dir_rel,
                                                     jboolean recursive, jboolean fav_only,
                                                     jint read_state) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return nullptr;
    auto books = g_core.lib->page(lib_id, to_std(env, search), sort_from(sort), desc == JNI_TRUE,
                                  offset, limit, to_std(env, dir_rel), recursive == JNI_TRUE,
                                  fav_only == JNI_TRUE, read_state);
    const jsize n = (jsize)books.size();

    jlongArray ids = env->NewLongArray(n);
    std::vector<jlong> idv(n);
    std::vector<jint> fav(n), rs(n), lp(n), pg(n);
    for (jsize i = 0; i < n; ++i) {
        idv[i] = books[i].id;
        fav[i] = books[i].favorite ? 1 : 0;
        rs[i] = books[i].read_state;
        lp[i] = books[i].last_page;
        pg[i] = books[i].pages;
    }
    env->SetLongArrayRegion(ids, 0, n, idv.data());

    jobjectArray titles = env->NewObjectArray(n, env->FindClass("java/lang/String"), nullptr);
    for (jsize i = 0; i < n; ++i)
        env->SetObjectArrayElement(titles, i, from_std(env, books[i].title));

    jobjectArray out = env->NewObjectArray(6, env->FindClass("java/lang/Object"), nullptr);
    env->SetObjectArrayElement(out, 0, ids);
    env->SetObjectArrayElement(out, 1, titles);
    env->SetObjectArrayElement(out, 2, to_int_array(env, fav));
    env->SetObjectArrayElement(out, 3, to_int_array(env, rs));
    env->SetObjectArrayElement(out, 4, to_int_array(env, lp));
    env->SetObjectArrayElement(out, 5, to_int_array(env, pg));
    return out;
}

// 单本完整文件名(书名 + ".扩展名"): 长按菜单"导出书名"用。
JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeBookFileName(JNIEnv* env, jobject, jlong book_id) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return env->NewStringUTF("");
    return from_std(env, g_core.lib->book_file_name(book_id));
}

JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeChildDirs(JNIEnv* env, jobject, jlong lib_id,
                                                          jstring parent_rel) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return env->NewStringUTF("[]");
    return from_std(env, json_dirs(g_core.lib->child_dirs(lib_id, to_std(env, parent_rel))));
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeRebuildDirs(JNIEnv*, jobject, jlong lib_id) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (g_core.lib) g_core.lib->rebuild_dirs(lib_id);
}

// ---------------------------------------------------------------- covers

// Object[3]: int[1] status (1 inflight, 2 ready, 3 unavailable),
//            int[2] {w,h}, byte[] rgba. Only when status==2 are w/h/rgba set.
JNIEXPORT jobjectArray JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeCoverPoll(JNIEnv* env, jobject, jlong book_id) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return nullptr;
    Book b = find_book(book_id);
    if (b.id == 0) return nullptr;

    // 状态机在原生侧：cover_poll() 驱动提交/重试/冷却，如实返回
    // 1=生成中、2=就绪、3=不可用（永久失败，或瞬态失败冷却中）。
    auto r = g_core.lib->cover_poll(b);
    ThumbPtr t = r.thumb;
    const int status = t ? 2 : r.status;

    jintArray st = env->NewIntArray(1);
    jint sv[1] = {status};
    env->SetIntArrayRegion(st, 0, 1, sv);

    jobjectArray out = env->NewObjectArray(3, env->FindClass("java/lang/Object"), nullptr);
    env->SetObjectArrayElement(out, 0, st);
    env->SetObjectArrayElement(out, 1, env->NewIntArray(2));
    env->SetObjectArrayElement(out, 2, env->NewByteArray(0));
    if (!t) return out;

    jintArray dims = env->NewIntArray(2);
    jint dv[2] = {t->w, t->h};
    env->SetIntArrayRegion(dims, 0, 2, dv);
    jbyteArray px = env->NewByteArray((jsize)t->bytes());
    env->SetByteArrayRegion(px, 0, (jsize)t->bytes(), (const jbyte*)t->pixels.data());
    env->SetObjectArrayElement(out, 1, dims);
    env->SetObjectArrayElement(out, 2, px);
    return out;
}

// 手动“重提失败封面”：清掉失败标记（含永久失败），返回重置条数。
// 可见单元格由 UI 侧立即重新轮询；其余随浏览懒重试。
JNIEXPORT jint JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeRetryFailedCovers(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return 0;
    return g_core.lib->retry_failed_covers();
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeForgetCover(JNIEnv*, jobject, jlong book_id) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return;
    if (Book b = find_book(book_id); b.id != 0) g_core.lib->forget_cover(b);
}

// ---------------------------------------------------------------- user state

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeSetFavorite(JNIEnv*, jobject, jlong book_id,
                                                            jboolean fav) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (g_core.lib) g_core.lib->set_favorite(book_id, fav == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeSetReadState(JNIEnv*, jobject, jlong book_id,
                                                             jint state) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (g_core.lib) g_core.lib->set_read_state(book_id, state);
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeSaveProgress(JNIEnv*, jobject, jlong book_id,
                                                             jint page, jint page_count) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return;
    g_core.lib->save_progress(book_id, page);
    // Mirror the Windows reader: opening marks "reading", finishing marks "read".
    Book b = find_book(book_id);
    if (b.id == 0) return;
    if (page >= page_count - 1 && page_count > 0)
        g_core.lib->set_read_state(book_id, 2);
    else if (b.read_state == 0)
        g_core.lib->set_read_state(book_id, 1);
}

JNIEXPORT jboolean JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeDeleteBook(JNIEnv*, jobject, jlong book_id) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return JNI_FALSE;
    return g_core.lib->delete_book(book_id) ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------- bookmarks / tags

JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeBookmarks(JNIEnv* env, jobject, jlong book_id) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return env->NewStringUTF("[]");
    return from_std(env, json_bookmarks(g_core.lib->bookmarks(book_id)));
}

JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeAllBookmarks(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return env->NewStringUTF("[]");
    return from_std(env, json_bookmarks(g_core.lib->all_bookmarks()));
}

JNIEXPORT jlong JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeAddBookmark(JNIEnv* env, jobject, jlong book_id,
                                                            jint page, jstring label) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return 0;
    // add_bookmark has no id return; verify via has_bookmark like the desktop UI.
    g_core.lib->add_bookmark(book_id, page, to_std(env, label));
    return g_core.lib->db().has_bookmark(book_id, page) ? 1 : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeRemoveBookmark(JNIEnv*, jobject, jlong bm_id) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return JNI_FALSE;
    return g_core.lib->remove_bookmark(bm_id) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeHasBookmark(JNIEnv*, jobject, jlong book_id,
                                                            jint page) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return JNI_FALSE;
    return g_core.lib->has_bookmark(book_id, page) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeAllTags(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return env->NewStringUTF("[]");
    return from_std(env, json_strings(g_core.lib->all_tags()));
}

JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeBookTags(JNIEnv* env, jobject, jlong book_id) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return env->NewStringUTF("[]");
    return from_std(env, json_strings(g_core.lib->book_tags(book_id)));
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeSetBookTags(JNIEnv* env, jobject, jlong book_id,
                                                            jstring tags_json) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return;
    std::vector<std::string> tags;
    json::Value v;
    if (json::parse(to_std(env, tags_json), v, nullptr) && v.is_array())
        for (const auto& t : v.as_array()) tags.push_back(t.as_string());
    g_core.lib->set_book_tags(book_id, tags);
}

JNIEXPORT jboolean JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeDeleteTag(JNIEnv* env, jobject, jstring name) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return JNI_FALSE;
    return g_core.lib->delete_tag(to_std(env, name)) ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------- reader

// int[3] {pageCount, lastPage, kind} or nullptr on failure.
JNIEXPORT jintArray JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeReaderOpen(JNIEnv* env, jobject, jlong book_id) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return nullptr;
    Book b = find_book(book_id);
    if (b.id == 0) {
        log_warn("reader open: book " + std::to_string(book_id) + " not found in db");
        return nullptr;
    }

    auto session = std::make_shared<ReaderSession>();
    session->book = b;
    std::string err;
    session->arch = std::shared_ptr<IArchive>(g_core.lib->open_book(b, &err));
    if (!session->arch) {
        log_warn("reader open failed: " + err + " (" + b.path + ")");
        return nullptr;
    }
    log_info("reader open ok: " + b.path + " entries=" + std::to_string(session->arch->count()));
    const int pages = (int)session->arch->count();
    if (pages > 0) {
        g_core.lib->db().update_pages(b.id, pages);
    }
    g_readers.clear(); // one book open at a time, like the desktop reader
    g_readers[book_id] = session;

    BookMeta m;
    g_core.lib->db().get_meta(b.id, m);
    std::vector<int> v{pages, m.last_page, (int)b.kind};
    return to_int_array(env, v);
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeReaderClose(JNIEnv*, jobject, jlong book_id) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    g_readers.erase(book_id);
}

// 后台整本翻译专用读取通道（BookTranslateJob）：**独立于 UI 的单本阅读器会话**
// （g_readers 是"同时只开一本"，UI 关书/换书会把它会话清掉；此后台会话自身存活，
//  且绝不去动 g_readers，不打扰正在阅读的书）。维持"最近一本"缓存，跨书自动重开。
static std::shared_ptr<ReaderSession> g_job_reader;
static int64_t g_job_book = 0;

JNIEXPORT jbyteArray JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeJobReadPage(JNIEnv* env, jobject, jlong book_id,
                                                            jint index) {
    std::shared_ptr<ReaderSession> s;
    {
        std::lock_guard<std::mutex> lk(g_bridge_mtx);
        if (!g_core.lib) return nullptr;
        if (g_job_book != book_id || !g_job_reader) {
            Book b = find_book(book_id);
            if (b.id == 0) return nullptr;
            auto session = std::make_shared<ReaderSession>();
            session->book = b;
            std::string err;
            session->arch = std::shared_ptr<IArchive>(g_core.lib->open_book(b, &err));
            if (!session->arch) {
                log_warn("job reader open failed: " + err + " (" + b.path + ")");
                g_job_reader.reset();
                g_job_book = 0;
                return nullptr;
            }
            g_job_reader = session;
            g_job_book = book_id;
            log_info("job reader open ok: " + b.path + " entries=" +
                     std::to_string(session->arch->count()));
        }
        s = g_job_reader;
    }
    if (!s) return nullptr;
    std::lock_guard<std::mutex> slk(s->mtx);
    if (index < 0 || index >= (int)s->arch->count()) return nullptr;
    std::vector<uint8_t> bytes;
    if (!s->arch->read((size_t)index, bytes) || bytes.empty()) return nullptr;
    jbyteArray out = env->NewByteArray((jsize)bytes.size());
    env->SetByteArrayRegion(out, 0, (jsize)bytes.size(), (const jbyte*)bytes.data());
    return out;
}

// Raw (still compressed) page bytes, for the Kotlin ImageDecoder fast path.
JNIEXPORT jbyteArray JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeReadPage(JNIEnv* env, jobject, jlong book_id,
                                                         jint index) {
    std::shared_ptr<ReaderSession> s;
    {
        std::lock_guard<std::mutex> lk(g_bridge_mtx);
        s = find_session(book_id);
    }
    if (!s) return nullptr;
    std::lock_guard<std::mutex> slk(s->mtx);
    if (index < 0 || index >= (int)s->arch->count()) return nullptr;
    std::vector<uint8_t> bytes;
    if (!s->arch->read((size_t)index, bytes) || bytes.empty()) return nullptr;
    jbyteArray out = env->NewByteArray((jsize)bytes.size());
    env->SetByteArrayRegion(out, 0, (jsize)bytes.size(), (const jbyte*)bytes.data());
    return out;
}

JNIEXPORT jint JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativePageCount(JNIEnv*, jobject, jlong book_id) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    auto s = find_session(book_id);
    return s ? (int)s->arch->count() : 0;
}

// C++ decode fallback for formats Android's ImageDecoder lacks
// (PSD/TGA/HDR/PNM/AVIF on old systems).
JNIEXPORT jobject JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeDecodePageRGBA(JNIEnv* env, jobject,
                                                               jbyteArray bytes, jint max_dim) {
    jsize n = env->GetArrayLength(bytes);
    if (n <= 0) return nullptr;
    jbyte* p = env->GetByteArrayElements(bytes, nullptr);
    ImageRGBA img;
    bool ok = decode_image((const uint8_t*)p, (size_t)n, img);
    env->ReleaseByteArrayElements(bytes, p, JNI_ABORT);
    if (!ok) return nullptr;
    if (max_dim > 0) {
        int longest = std::max(img.w, img.h);
        if (longest > max_dim) img = downscale_to_fit(img, max_dim, Resample::Box);
    }
    return image_bundle(env, img.w, img.h, img.pixels.data(), img.pixels.size());
}

// ---------------------------------------------------------------- translate

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeTranslateConfigure(JNIEnv* env, jobject,
                                                                   jstring url, jstring src,
                                                                   jstring dst, jint fwd,
                                                                   jint back, jint cache_mb,
                                                                   jboolean enabled) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib || !g_core.translator) return;
    Settings& st = *g_core.settings;
    if (url) st.set("translate_service_url", to_std(env, url));
    if (src) st.set("source_lang", to_std(env, src));
    if (dst) st.set("target_lang", to_std(env, dst));
    if (fwd >= 0) st.set_int("translate_prefetch_fwd", fwd);
    if (back >= 0) st.set_int("translate_prefetch_back", back);
    if (cache_mb > 0) st.set_int("translate_cache_mb", cache_mb);
    st.set_bool("translate_enabled", enabled == JNI_TRUE);
    g_core.translator->configure(st);
    g_core.translator->set_enabled(enabled == JNI_TRUE);
}

JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeTranslateHealth(JNIEnv* env, jobject,
                                                                jint timeout_ms) {
    std::string pipeline, err;
    // Run outside the bridge lock: health probes block up to the timeout.
    translate::TranslateEngine* engine = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_bridge_mtx);
        engine = g_core.translator.get();
    }
    if (!engine) return from_std(env, "{\"ok\":false,\"error\":\"not started\"}");
    bool ok = engine->client().health(&pipeline, &err, timeout_ms);
    json::Value o = json::Value::make_object();
    o.set("ok", ok);
    o.set("pipeline", pipeline);
    o.set("error", err);
    return from_std(env, o.dump());
}

JNIEXPORT jboolean JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeTranslateOpenBook(JNIEnv*, jobject,
                                                                  jlong book_id) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.translator || !g_core.lib) return JNI_FALSE;
    Book b = find_book(book_id);
    if (b.id == 0) return JNI_FALSE;

    // The engine reads page bytes through the live reader session.
    g_core.translator->set_page_reader([book_id](int page, std::vector<uint8_t>& out) {
        std::shared_ptr<ReaderSession> s;
        {
            std::lock_guard<std::mutex> lk(g_bridge_mtx);
            s = find_session(book_id);
        }
        if (!s) return false;
        std::lock_guard<std::mutex> slk(s->mtx);
        if (page < 0 || page >= (int)s->arch->count()) return false;
        return s->arch->read((size_t)page, out) && !out.empty();
    });
    g_core.translator->open_book(b);
    g_core.translator->set_enabled(true);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeTranslateCloseBook(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (g_core.translator) g_core.translator->close_book();
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeTranslateFocus(JNIEnv*, jobject, jint page) {
    // Focus changes also (re)queue the prefetch window.
    if (!g_core.translator) return;
    g_core.translator->set_focus(page);
    g_core.translator->request_around(page);
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeTranslateRequestWindow(JNIEnv*, jobject, jint page,
                                                                       jint back, jint fwd) {
    if (g_core.translator) g_core.translator->request_window(page, back, fwd);
}

JNIEXPORT jint JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeTranslateState(JNIEnv*, jobject, jint page) {
    if (!g_core.translator) return 0;
    switch (g_core.translator->state(page)) {
        case translate::PageState::Queued: return 1;
        case translate::PageState::Busy: return 2;
        case translate::PageState::Ready: return 3;
        case translate::PageState::Failed: return 4;
        default: return 0;
    }
}

JNIEXPORT jobject JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeTranslateGetPage(JNIEnv* env, jobject, jint page,
                                                                 jint max_dim) {
    if (!g_core.translator) return nullptr;
    auto img = g_core.translator->get(page);
    if (!img) return nullptr;
    if (max_dim > 0) {
        int longest = std::max(img->w, img->h);
        if (longest > max_dim) {
            ImageRGBA scaled = downscale_to_fit(*img, max_dim, Resample::Box);
            return image_bundle(env, scaled.w, scaled.h, scaled.pixels.data(), scaled.pixels.size());
        }
    }
    return image_bundle(env, img->w, img->h, img->pixels.data(), img->pixels.size());
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeTranslateClearCache(JNIEnv*, jobject) {
    if (g_core.translator) g_core.translator->clear_cache();
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeTranslateRetranslate(JNIEnv*, jobject, jint page) {
    if (g_core.translator) g_core.translator->retranslate_page(page);
}

JNIEXPORT jboolean JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeTranslateServiceUp(JNIEnv*, jobject) {
    if (!g_core.translator) return JNI_FALSE;
    return g_core.translator->service_up() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeTranslateLastError(JNIEnv* env, jobject) {
    if (!g_core.translator) return env->NewStringUTF("");
    return from_std(env, g_core.translator->last_error());
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeSetBookTranslateEnabled(JNIEnv*, jobject,
                                                                        jlong book_id,
                                                                        jboolean on) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (g_core.lib) g_core.lib->db().set_book_translate_enabled(book_id, on == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeGetBookTranslateEnabled(JNIEnv*, jobject,
                                                                        jlong book_id) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return JNI_FALSE;
    return g_core.lib->db().get_book_translate_enabled(book_id) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeClearBookArchive(JNIEnv*, jobject, jlong book_id) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (g_core.lib) g_core.lib->db().delete_text_archive(book_id);
}

// 设置页"释放全部翻译与封面"：清空全部翻译档案（返回删除行数）+ 封面缓存（磁盘+内存）。
JNIEXPORT jlong JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeClearAllTextArchive(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return 0;
    const int64_t n = g_core.lib->db().clear_text_archive();
    log_info("text archives cleared: " + std::to_string(n) + " books");
    return n;
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeClearAllCovers(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (g_core.lib) g_core.lib->clear_all_covers();
}

JNIEXPORT jlong JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeTranslateArchiveBytes(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return 0;
    return g_core.lib->db().text_archive_total_bytes();
}

JNIEXPORT jlong JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeTranslateEvict(JNIEnv*, jobject, jint mb) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib || mb <= 0) return 0;
    return g_core.lib->db().evict_text_archive((int64_t)mb * 1024 * 1024);
}

JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeGlossary(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib) return env->NewStringUTF("[]");
    json::Value arr = json::Value::make_array();
    for (const auto& kv : g_core.lib->db().glossary()) {
        json::Value o = json::Value::make_object();
        o.set("src", kv.first);
        o.set("dst", kv.second);
        arr.push_back(o);
    }
    return from_std(env, arr.dump());
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeGlossarySet(JNIEnv* env, jobject, jstring src,
                                                            jstring dst) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (g_core.lib) g_core.lib->db().set_glossary_term(to_std(env, src), to_std(env, dst));
}

JNIEXPORT void JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeGlossaryRemove(JNIEnv* env, jobject, jstring src) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (g_core.lib) g_core.lib->db().remove_glossary_term(to_std(env, src));
}

// ---------------------------------------------------------------- diagnostics

// ---- text archive access for the on-device engine (shares the sidecar schema)

JNIEXPORT jboolean JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeSaveTextArchive(JNIEnv* env, jobject,
                                                                jlong book_id, jint page,
                                                                jstring img_hash,
                                                                jstring pipeline,
                                                                jstring texts_json) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib || !g_core.translator) return JNI_FALSE;
    translate::PageTexts pt;
    pt.page = page;
    pt.img_hash = to_std(env, img_hash);
    pt.pipeline = to_std(env, pipeline);
    json::Value v;
    if (json::parse(to_std(env, texts_json), v, nullptr) && v.is_array())
        for (const auto& s : v.as_array()) pt.texts.push_back(s.as_string());
    pt.n = (int)pt.texts.size();
    if (pt.n == 0) return JNI_FALSE;
    return g_core.translator->client().save_archive(book_id, page, pt) ? JNI_TRUE : JNI_FALSE;
}

/** JSON {n, imgHash, pipeline, texts[]} or null when no archive entry. */
JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeLoadTextArchive(JNIEnv* env, jobject,
                                                                jlong book_id, jint page) {
    std::lock_guard<std::mutex> lk(g_bridge_mtx);
    if (!g_core.lib || !g_core.translator) return nullptr;
    translate::PageTexts pt;
    if (!g_core.translator->client().load_archive(book_id, page, pt)) return nullptr;
    json::Value o = json::Value::make_object();
    o.set("n", (int64_t)pt.n);
    o.set("imgHash", pt.img_hash);
    o.set("pipeline", pt.pipeline);
    json::Value arr = json::Value::make_array();
    for (const auto& s : pt.texts) arr.push_back(s);
    o.set("texts", arr);
    return from_std(env, o.dump());
}

JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeSelftest(JNIEnv* env, jobject, jint scale,
                                                         jstring scratch_dir) {
    std::string dir = to_std(env, scratch_dir);
    if (dir.empty()) dir = "/data/local/tmp";
    std::string report;
    auto t0 = std::chrono::steady_clock::now();
    auto ms = [&] {
        return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0)
            .count();
    };

    Database db;
    std::string err;
    if (!db.open(dir + "/cs_selftest.db", &err)) {
        return from_std(env, "open failed: " + err);
    }
    ::remove((dir + "/cs_selftest.db").c_str());
    db.migrate();

    const int64_t token = db.next_scan_token();
    db.begin();
    for (int i = 0; i < scale; ++i) {
        Book b;
        b.lib_id = 1;
        b.path = "/sdcard/Comics/book_" + std::to_string(i) + ".cbz";
        b.rel_dir = (i % 100 == 0) ? "" : "vol" + std::to_string(i % 50);
        b.kind = BookKind::Archive;
        b.title = "Book " + std::to_string(i);
        b.ext = "cbz";
        b.size = 1024 * 1024 + i;
        b.mtime = 1700000000 + i;
        db.upsert_book(b, token);
        if ((i + 1) % 20000 == 0) {
            db.commit();
            db.begin();
        }
    }
    db.commit();
    report += "insert " + std::to_string(scale) + " rows: " + std::to_string(ms()) + " ms\n";
    t0 = std::chrono::steady_clock::now();

    int64_t n = db.count_books(1, "");
    report += "count_books -> " + std::to_string(n) + ": " + std::to_string(ms()) + " ms\n";
    t0 = std::chrono::steady_clock::now();

    auto rows = db.page_books(1, "", SortKey::Title, true, scale - 200, 200);
    report += "page 200 @max offset -> " + std::to_string(rows.size()) + ": " +
              std::to_string(ms()) + " ms\n";
    t0 = std::chrono::steady_clock::now();

    rows = db.page_books(1, "Book 9999", SortKey::Title, false, 0, 50);
    report += "search 'Book 9999' -> " + std::to_string(rows.size()) + ": " +
              std::to_string(ms()) + " ms\n";
    return from_std(env, report);
}

} // extern "C"
