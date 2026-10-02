#pragma once
// Minimal thread-safe logger. Writes to a UTF-8 text file and to the debugger.
#include <string>

namespace cs {

// Opens (or reopens) the log file. Safe to call once at startup.
void log_open(const std::string& utf8_path);
void log_close();

void log_info(const std::string& msg);
void log_warn(const std::string& msg);
void log_error(const std::string& msg);

} // namespace cs
