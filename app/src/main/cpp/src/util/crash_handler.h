#pragma once
// Installs an unhandled-exception handler that logs the faulting module and
// writes a minidump, so a crash leaves usable evidence behind.
#include <string>

namespace cs {

// `dump_dir_utf8` is created if needed; dumps land there as crash_<ts>.dmp.
void install_crash_handler(const std::string& dump_dir_utf8);

} // namespace cs
