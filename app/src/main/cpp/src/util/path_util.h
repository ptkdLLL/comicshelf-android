#pragma once
// UTF-8 / wide-string helpers plus small path utilities.
#include <cstdint>
#include <filesystem>
#include <string>

namespace cs::paths {

std::wstring to_wide(const std::string& utf8);
std::string to_utf8(const std::wstring& w);

std::filesystem::path from_utf8(const std::string& utf8);
std::string path_to_utf8(const std::filesystem::path& p);

// Extension in lower case, WITHOUT the leading dot (e.g. "cbz").
std::string lower_ext(const std::filesystem::path& p);
// File name without extension, as UTF-8.
std::string stem_utf8(const std::filesystem::path& p);

bool is_image_ext(const std::string& ext);
bool is_zip_ext(const std::string& ext);
bool is_rar_ext(const std::string& ext);
bool is_archive_ext(const std::string& ext);

// Stable 64-bit FNV-1a hash, used for cache keys.
uint64_t fnv1a64(const std::string& data);

// Natural ("chapter2" < "chapter10") comparison of UTF-8 strings.
bool natural_less(const std::string& a, const std::string& b);

// Replace characters that are illegal in Windows file names.
std::string sanitize_filename(const std::string& s);

// Directory containing `abs`, expressed relative to `root`, as UTF-8 with
// '/' separators. Returns "" when they are the same directory, and also when
// `abs` lies outside `root`.
std::string relative_dir_utf8(const std::filesystem::path& abs,
                              const std::filesystem::path& root);

} // namespace cs::paths
