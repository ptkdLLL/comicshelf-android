#include "core/settings.h"

#include <cstdlib>
#include <fstream>
#include <sstream>

namespace cs {

void Settings::load(const std::string& utf8_path) {
    path_ = utf8_path;
    map_.clear();

    std::ifstream in(utf8_path, std::ios::binary);
    if (!in) return;

    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#' || line[0] == ';') continue;
        auto eq = line.find('=');
        if (eq == std::string::npos) continue;
        std::string key = line.substr(0, eq);
        std::string val = line.substr(eq + 1);
        // Trim CR and surrounding spaces.
        while (!val.empty() && (val.back() == '\r' || val.back() == '\n' || val.back() == ' '))
            val.pop_back();
        map_[key] = val;
    }
}

bool Settings::save() const {
    std::ofstream out(path_, std::ios::binary | std::ios::trunc);
    if (!out) return false;
    out << "# ComicShelf settings\n";
    for (const auto& kv : map_)
        out << kv.first << '=' << kv.second << '\n';
    return true;
}

std::string Settings::get(const std::string& key, const std::string& def) const {
    auto it = map_.find(key);
    return it == map_.end() ? def : it->second;
}

int Settings::get_int(const std::string& key, int def) const {
    auto it = map_.find(key);
    if (it == map_.end()) return def;
    try {
        return std::stoi(it->second);
    } catch (...) {
        return def;
    }
}

bool Settings::get_bool(const std::string& key, bool def) const {
    auto it = map_.find(key);
    if (it == map_.end()) return def;
    const std::string& v = it->second;
    return v == "1" || v == "true" || v == "True" || v == "on" || v == "yes";
}

float Settings::get_float(const std::string& key, float def) const {
    auto it = map_.find(key);
    if (it == map_.end()) return def;
    try {
        return std::stof(it->second);
    } catch (...) {
        return def;
    }
}

void Settings::set(const std::string& key, const std::string& value) { map_[key] = value; }
void Settings::set_int(const std::string& key, int value) { map_[key] = std::to_string(value); }
void Settings::set_bool(const std::string& key, bool value) { map_[key] = value ? "1" : "0"; }
void Settings::set_float(const std::string& key, float value) {
    std::ostringstream ss;
    ss << value;
    map_[key] = ss.str();
}

} // namespace cs
