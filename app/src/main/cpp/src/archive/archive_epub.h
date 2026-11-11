#pragma once
// EPUB backend: ZIP random access + minimal OPF cover resolution. Page images
// are exposed through IArchive (cover first) so the existing async cover cache
// works unchanged; text reading uses read_named(OPF/XHTML/image) via JNI.
#include "archive/archive.h"

#include <memory>
#include <vector>

namespace cs {

namespace vfs { class Vfs; }

std::unique_ptr<IArchive> make_epub_archive(const std::shared_ptr<vfs::Vfs>& vfs,
                                             const std::string& rel, std::string* err);

// Extracts <rootfile full-path="..."> from META-INF/container.xml.
bool epub_rootfile_from_container(const std::vector<uint8_t>& xml, std::string* out_path);

} // namespace cs
