#pragma once
// Tiny key=value settings store (no external JSON dependency).
#include <map>
#include <string>

namespace cs {

class Settings {
public:
    // Loads from an INI-like file; missing file is not an error.
    void load(const std::string& utf8_path);
    bool save() const;

    std::string get(const std::string& key, const std::string& def) const;
    int get_int(const std::string& key, int def) const;
    bool get_bool(const std::string& key, bool def) const;
    float get_float(const std::string& key, float def) const;

    void set(const std::string& key, const std::string& value);
    void set_int(const std::string& key, int value);
    void set_bool(const std::string& key, bool value);
    void set_float(const std::string& key, float value);

    const std::string& path() const { return path_; }

private:
    std::string path_;
    std::map<std::string, std::string> map_;
};

} // namespace cs
