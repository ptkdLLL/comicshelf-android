#include "util/path_util.h"

#include <algorithm>
#include <cctype>

#ifdef _WIN32
#include <windows.h>
#endif

namespace cs::paths {

std::wstring to_wide(const std::string& utf8) {
    if (utf8.empty()) return {};
#ifdef _WIN32
    int n = MultiByteToWideChar(CP_UTF8, 0, utf8.c_str(), (int)utf8.size(), nullptr, 0);
    if (n <= 0) return {};
    std::wstring w((size_t)n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, utf8.c_str(), (int)utf8.size(), w.data(), n);
    return w;
#else
    return std::wstring(utf8.begin(), utf8.end());
#endif
}

std::string to_utf8(const std::wstring& w) {
    if (w.empty()) return {};
#ifdef _WIN32
    int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), nullptr, 0, nullptr, nullptr);
    if (n <= 0) return {};
    std::string s((size_t)n, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), s.data(), n, nullptr, nullptr);
    return s;
#else
    return std::string(w.begin(), w.end());
#endif
}

std::filesystem::path from_utf8(const std::string& utf8) {
#ifdef _WIN32
    return std::filesystem::path(to_wide(utf8));
#else
    // POSIX: the native narrow encoding is UTF-8 (Android/macOS/Linux).
    return std::filesystem::path(utf8);
#endif
}

std::string path_to_utf8(const std::filesystem::path& p) {
#ifdef _WIN32
    return to_utf8(p.wstring());
#else
    return p.generic_string();
#endif
}

std::string lower_ext(const std::filesystem::path& p) {
#ifdef _WIN32
    std::string e = to_utf8(p.extension().wstring());
#else
    std::string e = p.extension().string();
#endif
    if (!e.empty() && e[0] == '.') e.erase(e.begin());
    std::transform(e.begin(), e.end(), e.begin(),
                   [](unsigned char c) { return (char)std::tolower(c); });
    return e;
}

std::string stem_utf8(const std::filesystem::path& p) {
#ifdef _WIN32
    return to_utf8(p.stem().wstring());
#else
    return p.stem().string();
#endif
}

bool is_image_ext(const std::string& ext) {
    static const char* kExts[] = {"jpg", "jpeg", "png", "bmp", "gif", "webp", "tif", "tiff", "jfif", "avif", "jxl"};
    for (const char* e : kExts)
        if (ext == e) return true;
    return false;
}

bool is_zip_ext(const std::string& ext) {
    return ext == "zip" || ext == "cbz";
}

bool is_rar_ext(const std::string& ext) {
    return ext == "rar" || ext == "cbr";
}

bool is_archive_ext(const std::string& ext) {
    return is_zip_ext(ext) || is_rar_ext(ext);
}

uint64_t fnv1a64(const std::string& data) {
    uint64_t h = 1469598103934665603ull;
    for (unsigned char c : data) {
        h ^= (uint64_t)c;
        h *= 1099511628211ull;
    }
    return h;
}

namespace {
bool is_digit(char c) { return c >= '0' && c <= '9'; }
} // namespace

bool natural_less(const std::string& a, const std::string& b) {
    size_t i = 0, j = 0;
    const size_t na = a.size(), nb = b.size();
    while (i < na && j < nb) {
        if (is_digit(a[i]) && is_digit(b[j])) {
            size_t i0 = i, j0 = j;
            while (i < na && is_digit(a[i])) ++i;
            while (j < nb && is_digit(b[j])) ++j;
            // Strip leading zeros for the numeric comparison.
            size_t ia = i0, jb = j0;
            while (ia + 1 < i && a[ia] == '0') ++ia;
            while (jb + 1 < j && b[jb] == '0') ++jb;
            size_t la = i - ia, lb = j - jb;
            if (la != lb) return la < lb;
            int cmp = a.compare(ia, la, b, jb, lb);
            if (cmp != 0) return cmp < 0;
        } else {
            char ca = (char)std::tolower((unsigned char)a[i]);
            char cb = (char)std::tolower((unsigned char)b[j]);
            if (ca != cb) return ca < cb;
            ++i;
            ++j;
        }
    }
    return (na - i) < (nb - j);
}

std::string relative_dir_utf8(const std::filesystem::path& abs,
                              const std::filesystem::path& root) {
    std::filesystem::path rel = abs.parent_path().lexically_relative(root);
    if (rel.empty()) return {};
#ifdef _WIN32
    // generic_wstring() keeps '/' separators; convert to UTF-8 ourselves so
    // non-ASCII (e.g. Japanese) folder names survive.
    std::string s = to_utf8(rel.generic_wstring());
#else
    std::string s = rel.generic_string();
#endif
    if (s == ".") return {};
    if (s.rfind("..", 0) == 0) return {}; // outside the library root
    if (s.rfind("./", 0) == 0) s.erase(0, 2);
    while (!s.empty() && s.back() == '/') s.pop_back();
    return s;
}

std::string sanitize_filename(const std::string& s) {
    std::string out;
    out.reserve(s.size());
    for (char c : s) {
        if (c == '<' || c == '>' || c == ':' || c == '"' || c == '/' ||
            c == '\\' || c == '|' || c == '?' || c == '*' || (unsigned char)c < 32)
            out.push_back('_');
        else
            out.push_back(c);
    }
    if (out.size() > 100) out.resize(100);
    return out;
}

} // namespace cs::paths
