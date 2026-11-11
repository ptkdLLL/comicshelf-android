// EPUB backend. EPUB is a ZIP; we reuse the same random-access path as
// archive_zip.cpp (miniz + vfs::ByteSource::pread) so SMB books are never
// downloaded as a whole. The IArchive entries are raster page images with the
// OPF cover first — that is all the existing async cover pipeline needs.
// Text reading uses read_named() for the OPF/XHTML/image hrefs.
#include "archive/archive_epub.h"

#include "util/logger.h"
#include "util/path_util.h"
#include "vfs/byte_source.h"
#include "vfs/vfs.h"

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

#include "miniz.h"

namespace cs {
namespace {

size_t epub_read_cb(void* opaque, mz_uint64 file_ofs, void* buf, size_t n) {
    auto* src = static_cast<vfs::ByteSource*>(opaque);
    return src->pread(file_ofs, buf, n) ? n : 0;
}

std::string lower_str(std::string s) {
    for (auto& c : s) c = (char)std::tolower((unsigned char)c);
    return s;
}

std::string dirname_of(const std::string& p) {
    const auto slash = p.find_last_of('/');
    return slash == std::string::npos ? std::string() : p.substr(0, slash);
}

std::string ext_of(const std::string& p) {
    const auto dot = p.find_last_of('.');
    if (dot == std::string::npos || dot + 1 >= p.size()) return {};
    return lower_str(p.substr(dot + 1));
}

bool is_raster_name(const std::string& p) {
    static const char* k[] = {"jpg", "jpeg", "png", "gif", "webp", "bmp", "avif"};
    const std::string e = ext_of(p);
    for (const char* x : k)
        if (e == x) return true;
    return false;
}

bool is_xhtml_name(const std::string& p) {
    const std::string e = ext_of(p);
    return e == "xhtml" || e == "html" || e == "htm";
}

std::string xml_unescape(std::string s) {
    struct Ent { const char* a; const char* b; };
    static const Ent k[] = {
        {"&amp;", "&"}, {"&lt;", "<"}, {"&gt;", ">"}, {"&quot;", "\""}, {"&apos;", "'"},
    };
    for (const auto& e : k) {
        size_t p = 0;
        while ((p = s.find(e.a, p)) != std::string::npos) {
            s.replace(p, std::strlen(e.a), e.b);
            p += std::strlen(e.b);
        }
    }
    return s;
}

// Attribute lookup bounded to a tag name (avoids matching data-name/class-name).
std::string xml_attr(const std::string& tag, const char* name) {
    const std::string key = name;
    size_t p = 0;
    while ((p = tag.find(key, p)) != std::string::npos) {
        if (p > 0) {
            const char prev = tag[p - 1];
            if (std::isalnum((unsigned char)prev) || prev == '_' || prev == '-' ||
                prev == ':') {
                p += key.size();
                continue;
            }
        }
        size_t q = p + key.size();
        while (q < tag.size() && std::isspace((unsigned char)tag[q])) ++q;
        if (q >= tag.size() || tag[q] != '=') {
            p += key.size();
            continue;
        }
        ++q;
        while (q < tag.size() && std::isspace((unsigned char)tag[q])) ++q;
        if (q >= tag.size() || (tag[q] != '"' && tag[q] != '\'')) return {};
        const char quote = tag[q++];
        const size_t e = tag.find(quote, q);
        if (e == std::string::npos) return {};
        return xml_unescape(tag.substr(q, e - q));
    }
    return {};
}

std::vector<std::string> xml_tags(const std::string& xml, const char* name) {
    std::vector<std::string> out;
    const std::string pat = std::string("<") + name;
    size_t p = 0;
    while ((p = xml.find(pat, p)) != std::string::npos) {
        const size_t q = p + pat.size();
        if (q < xml.size() &&
            !(std::isspace((unsigned char)xml[q]) || xml[q] == '>' || xml[q] == '/')) {
            p = q;
            continue;
        }
        const size_t e = xml.find('>', q);
        if (e == std::string::npos) break;
        out.push_back(xml.substr(p, e - p + 1));
        p = e + 1;
    }
    return out;
}

std::string percent_decode(std::string s) {
    std::string out;
    out.reserve(s.size());
    auto hex = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    };
    for (size_t i = 0; i < s.size(); ++i) {
        if (s[i] == '%' && i + 2 < s.size()) {
            const int a = hex(s[i + 1]), b = hex(s[i + 2]);
            if (a >= 0 && b >= 0) {
                out.push_back((char)((a << 4) | b));
                i += 2;
                continue;
            }
        }
        out.push_back(s[i]);
    }
    return out;
}

// Resolves an OPF/XHTML href into a ZIP entry name (relative to base_dir).
std::string resolve_href(const std::string& base_dir, std::string href) {
    const auto cut = href.find_first_of("#?");
    if (cut != std::string::npos) href.resize(cut);
    href = percent_decode(href);
    std::vector<std::string> parts;
    if (!href.empty() && href[0] == '/') {
        href.erase(0, 1);  // ZIP-root absolute: ignore OPF directory
    } else if (!base_dir.empty()) {
        size_t p = 0;
        while (true) {
            const auto s = base_dir.find('/', p);
            parts.push_back(base_dir.substr(p, s == std::string::npos ? std::string::npos : s - p));
            if (s == std::string::npos) break;
            p = s + 1;
        }
    }
    size_t p = 0;
    while (true) {
        const auto s = href.find('/', p);
        const std::string part =
            href.substr(p, s == std::string::npos ? std::string::npos : s - p);
        if (part.empty() || part == ".") {
            // skip
        } else if (part == "..") {
            if (!parts.empty()) parts.pop_back();
        } else {
            parts.push_back(part);
        }
        if (s == std::string::npos) break;
        p = s + 1;
    }
    std::string out;
    for (size_t i = 0; i < parts.size(); ++i) {
        if (i) out.push_back('/');
        out += parts[i];
    }
    return out;
}

std::string first_image_in_xhtml(const std::string& xml, const std::string& base_dir) {
    for (const auto& t : xml_tags(xml, "img")) {
        const auto src = xml_attr(t, "src");
        if (!src.empty()) return resolve_href(base_dir, src);
    }
    for (const auto& t : xml_tags(xml, "image")) {
        auto href = xml_attr(t, "xlink:href");
        if (href.empty()) href = xml_attr(t, "href");
        if (!href.empty()) return resolve_href(base_dir, href);
    }
    return {};
}

class EpubArchive : public IArchive {
public:
    ~EpubArchive() override {
        if (opened_) mz_zip_reader_end(&zip_);
    }

    bool open(const std::shared_ptr<vfs::Vfs>& vfs, const std::string& rel,
              std::string* err) {
        src_ = vfs::open_byte_source_on(vfs, rel, err);
        if (!src_) return false;
        zip_.m_pRead = epub_read_cb;
        zip_.m_pIO_opaque = src_.get();
        if (src_->size() == 0 || !mz_zip_reader_init(&zip_, src_->size(), 0)) {
            if (err) *err = "miniz: not a valid zip";
            return false;
        }
        opened_ = true;
        collect_names();

        std::vector<uint8_t> container;
        if (read_named("META-INF/container.xml", container)) {
            epub_rootfile_from_container(container, &opf_path_);
        }
        if (opf_path_.empty()) opf_path_ = find_opf_by_name();
        opf_dir_ = dirname_of(opf_path_);

        std::vector<uint8_t> opf;
        std::vector<std::string> image_hrefs;
        if (!opf_path_.empty() && read_named(opf_path_, opf)) {
            const std::string xml((const char*)opf.data(), opf.size());
            spine_count_ = (int)xml_tags(xml, "itemref").size();
            parse_opf_cover(xml, opf_dir_, &cover_href_, &image_hrefs);
        }

        // guide type="cover" often points at a Cover.xhtml wrapper, not the JPG.
        if (!cover_href_.empty() && is_xhtml_name(cover_href_)) {
            std::vector<uint8_t> cover_doc;
            if (read_named(cover_href_, cover_doc)) {
                const std::string doc((const char*)cover_doc.data(), cover_doc.size());
                const std::string img = first_image_in_xhtml(doc, dirname_of(cover_href_));
                cover_href_ = img;
            } else {
                cover_href_.clear();
            }
        }
        if (!cover_href_.empty() && !is_raster_name(cover_href_)) cover_href_.clear();

        if (image_hrefs.empty()) {
            image_hrefs = all_raster_names();
            std::sort(image_hrefs.begin(), image_hrefs.end(),
                      [](const std::string& a, const std::string& b) {
                          return paths::natural_less(a, b);
                      });
        }
        build_entries(cover_href_, image_hrefs);

        if (opf_path_.empty() && entries_.empty()) {
            if (err) *err = "not an epub (no opf/images)";
            return false;
        }
        log_info("epub open: " + rel + " opf=" + opf_path_ + " pages=" +
                 std::to_string(entries_.size()) + " cover=" + cover_href_);
        return true;
    }

    const std::vector<ArchiveEntry>& entries() const override { return entries_; }

    bool read(size_t i, std::vector<uint8_t>& out) override {
        std::lock_guard<std::mutex> lk(mtx_);
        if (!opened_ || i >= entries_.size()) return false;
        return extract_uindex(entries_[i].uindex, out);
    }

    bool read_named(const std::string& name, std::vector<uint8_t>& out) override {
        std::lock_guard<std::mutex> lk(mtx_);
        const auto it = name_to_uindex_.find(name);
        if (it == name_to_uindex_.end()) return false;
        return extract_uindex(it->second, out);
    }

    std::vector<std::string> entry_names() const override { return names_; }

    int page_count() const override {
        return spine_count_ > 0 ? spine_count_ : (int)entries_.size();
    }

private:
    void collect_names() {
        const mz_uint n = mz_zip_reader_get_num_files(&zip_);
        names_.reserve(n);
        for (mz_uint i = 0; i < n; ++i) {
            mz_zip_archive_file_stat st;
            if (!mz_zip_reader_file_stat(&zip_, i, &st)) continue;
            if (st.m_is_directory) continue;
            std::string name = st.m_filename ? st.m_filename : "";
            if (name.empty()) continue;
            names_.push_back(name);
            name_to_uindex_.emplace(name, i);
        }
    }

    std::string find_opf_by_name() const {
        for (const auto& n : names_)
            if (ext_of(n) == "opf") return n;
        return {};
    }

    std::vector<std::string> all_raster_names() const {
        std::vector<std::string> out;
        for (const auto& n : names_)
            if (is_raster_name(n)) out.push_back(n);
        return out;
    }

    bool extract_uindex(mz_uint index, std::vector<uint8_t>& out) {
        mz_zip_archive_file_stat st;
        if (!opened_ || !mz_zip_reader_file_stat(&zip_, index, &st)) return false;
        if (st.m_uncomp_size == 0) {
            out.clear();
            return true;
        }
        out.resize((size_t)st.m_uncomp_size);
        return mz_zip_reader_extract_to_mem(&zip_, index, out.data(), out.size(), 0) != 0;
    }

    void parse_opf_cover(const std::string& xml, const std::string& opf_dir,
                         std::string* cover_href, std::vector<std::string>* image_hrefs) {
        struct Item {
            std::string href;
            std::string media_type;
            std::string properties;
        };
        std::unordered_map<std::string, Item> items;
        const auto item_tags = xml_tags(xml, "item");
        for (const auto& t : item_tags) {
            const auto id = xml_attr(t, "id");
            const auto href = xml_attr(t, "href");
            if (id.empty() || href.empty()) continue;
            Item it;
            it.href = resolve_href(opf_dir, href);
            it.media_type = xml_attr(t, "media-type");
            it.properties = xml_attr(t, "properties");
            if (it.media_type.empty()) {
                if (is_raster_name(it.href)) it.media_type = "image/*";
                else if (is_xhtml_name(it.href)) it.media_type = "application/xhtml+xml";
            }
            items.emplace(id, it);
            if (it.media_type.rfind("image/", 0) == 0 && is_raster_name(it.href))
                image_hrefs->push_back(it.href);
            if (cover_href->empty() && it.properties.find("cover-image") != std::string::npos &&
                is_raster_name(it.href)) {
                *cover_href = it.href;
            }
        }
        if (cover_href->empty()) {
            for (const auto& t : xml_tags(xml, "meta")) {
                if (xml_attr(t, "name") != "cover") continue;
                const auto content = xml_attr(t, "content");
                if (content.empty()) break;
                const auto it = items.find(content);
                if (it != items.end()) {
                    *cover_href = it->second.href;
                } else {
                    const std::string resolved = resolve_href(opf_dir, content);
                    if (is_raster_name(resolved)) *cover_href = resolved;
                }
                break;
            }
        }
        if (cover_href->empty()) {
            for (const auto& t : xml_tags(xml, "reference")) {
                if (xml_attr(t, "type") != "cover") continue;
                const auto href = xml_attr(t, "href");
                if (!href.empty()) *cover_href = resolve_href(opf_dir, href);
                break;
            }
        }
        if (cover_href->empty() && !image_hrefs->empty()) *cover_href = image_hrefs->front();
    }

    void build_entries(const std::string& cover_href,
                       const std::vector<std::string>& image_hrefs) {
        std::unordered_set<std::string> seen;
        auto add = [&](const std::string& href) {
            if (href.empty() || !seen.insert(href).second) return;
            const auto it = name_to_uindex_.find(href);
            if (it == name_to_uindex_.end()) return;
            mz_zip_archive_file_stat st;
            if (!mz_zip_reader_file_stat(&zip_, it->second, &st) || st.m_is_directory) return;
            ArchiveEntry e;
            e.name = href;
            e.size = (uint64_t)st.m_uncomp_size;
            e.uindex = (uint64_t)it->second;
            entries_.push_back(std::move(e));
        };
        add(cover_href);
        for (const auto& h : image_hrefs) add(h);
    }

    mz_zip_archive zip_{};
    std::shared_ptr<vfs::ByteSource> src_;
    bool opened_ = false;
    std::vector<ArchiveEntry> entries_;
    std::vector<std::string> names_;
    std::unordered_map<std::string, mz_uint> name_to_uindex_;
    std::string opf_path_;
    std::string opf_dir_;
    std::string cover_href_;
    int spine_count_ = 0;
    std::mutex mtx_;
};

} // namespace

bool epub_rootfile_from_container(const std::vector<uint8_t>& xml, std::string* out_path) {
    if (!out_path) return false;
    const std::string s((const char*)xml.data(), xml.size());
    for (const auto& t : xml_tags(s, "rootfile")) {
        const auto p = xml_attr(t, "full-path");
        if (!p.empty()) {
            *out_path = p;
            return true;
        }
    }
    return false;
}

std::unique_ptr<IArchive> make_epub_archive(const std::shared_ptr<vfs::Vfs>& vfs,
                                             const std::string& rel, std::string* err) {
    auto a = std::make_unique<EpubArchive>();
    if (!a->open(vfs, rel, err)) return nullptr;
    return a;
}

} // namespace cs
