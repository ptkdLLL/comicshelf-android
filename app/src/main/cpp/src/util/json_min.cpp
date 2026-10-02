#include "util/json_min.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>

namespace cs::json {
namespace {

struct Parser {
    const char* p = nullptr;
    const char* end = nullptr;
    std::string err;

    void skip_ws() {
        while (p < end && (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n')) ++p;
    }
    bool fail(const char* msg) {
        if (err.empty()) err = msg;
        return false;
    }
    bool at(char c) const { return p < end && *p == c; }

    static void append_utf8(std::string& out, unsigned cp) {
        if (cp <= 0x7F) {
            out.push_back((char)cp);
        } else if (cp <= 0x7FF) {
            out.push_back((char)(0xC0 | (cp >> 6)));
            out.push_back((char)(0x80 | (cp & 0x3F)));
        } else if (cp <= 0xFFFF) {
            out.push_back((char)(0xE0 | (cp >> 12)));
            out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char)(0x80 | (cp & 0x3F)));
        } else {
            out.push_back((char)(0xF0 | (cp >> 18)));
            out.push_back((char)(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char)(0x80 | (cp & 0x3F)));
        }
    }

    bool parse_hex4(unsigned& v) {
        v = 0;
        for (int i = 0; i < 4; ++i) {
            if (p >= end) return false;
            char c = *p++;
            unsigned d;
            if (c >= '0' && c <= '9')
                d = (unsigned)(c - '0');
            else if (c >= 'a' && c <= 'f')
                d = (unsigned)(c - 'a' + 10);
            else if (c >= 'A' && c <= 'F')
                d = (unsigned)(c - 'A' + 10);
            else
                return false;
            v = (v << 4) | d;
        }
        return true;
    }

    bool parse_string(std::string& out) {
        if (!at('"')) return fail("expected string");
        ++p;
        out.clear();
        while (p < end) {
            char c = *p++;
            if (c == '"') return true;
            if (c != '\\') {
                out.push_back(c);
                continue;
            }
            if (p >= end) return fail("bad escape");
            char e = *p++;
            switch (e) {
                case '"': out.push_back('"'); break;
                case '\\': out.push_back('\\'); break;
                case '/': out.push_back('/'); break;
                case 'b': out.push_back('\b'); break;
                case 'f': out.push_back('\f'); break;
                case 'n': out.push_back('\n'); break;
                case 'r': out.push_back('\r'); break;
                case 't': out.push_back('\t'); break;
                case 'u': {
                    unsigned cp = 0;
                    if (!parse_hex4(cp)) return fail("bad \\u escape");
                    // Surrogate pair.
                    if (cp >= 0xD800 && cp <= 0xDBFF && p + 1 < end && p[0] == '\\' && p[1] == 'u') {
                        p += 2;
                        unsigned lo = 0;
                        if (!parse_hex4(lo)) return fail("bad low surrogate");
                        if (lo >= 0xDC00 && lo <= 0xDFFF)
                            cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                        else
                            append_utf8(out, 0xFFFD);
                    }
                    append_utf8(out, cp);
                    break;
                }
                default: return fail("unknown escape");
            }
        }
        return fail("unterminated string");
    }

    bool parse_value(Value& out) {
        skip_ws();
        if (p >= end) return fail("unexpected end");
        char c = *p;
        if (c == '{') return parse_object(out);
        if (c == '[') return parse_array(out);
        if (c == '"') {
            std::string s;
            if (!parse_string(s)) return false;
            out = Value(std::move(s));
            return true;
        }
        if (c == 't' && end - p >= 4 && std::string(p, p + 4) == "true") {
            p += 4;
            out = Value(true);
            return true;
        }
        if (c == 'f' && end - p >= 5 && std::string(p, p + 5) == "false") {
            p += 5;
            out = Value(false);
            return true;
        }
        if (c == 'n' && end - p >= 4 && std::string(p, p + 4) == "null") {
            p += 4;
            out = Value(nullptr);
            return true;
        }
        return parse_number(out);
    }

    bool parse_number(Value& out) {
        const char* start = p;
        if (p < end && (*p == '-' || *p == '+')) ++p;
        while (p < end && ((*p >= '0' && *p <= '9') || *p == '.' || *p == 'e' || *p == 'E' ||
                           *p == '+' || *p == '-'))
            ++p;
        if (p == start) return fail("invalid number");
        std::string tmp(start, p);
        char* endp = nullptr;
        double d = std::strtod(tmp.c_str(), &endp);
        if (endp == tmp.c_str() || !std::isfinite(d)) return fail("invalid number");
        out = Value(d);
        return true;
    }

    bool parse_array(Value& out) {
        ++p; // '['
        out = Value::make_array();
        skip_ws();
        if (at(']')) {
            ++p;
            return true;
        }
        for (;;) {
            Value v;
            if (!parse_value(v)) return false;
            out.push_back(std::move(v));
            skip_ws();
            if (at(',')) {
                ++p;
                continue;
            }
            if (at(']')) {
                ++p;
                return true;
            }
            return fail("expected , or ] in array");
        }
    }

    bool parse_object(Value& out) {
        ++p; // '{'
        out = Value::make_object();
        skip_ws();
        if (at('}')) {
            ++p;
            return true;
        }
        for (;;) {
            skip_ws();
            std::string key;
            if (!parse_string(key)) return false;
            skip_ws();
            if (!at(':')) return fail("expected :");
            ++p;
            Value v;
            if (!parse_value(v)) return false;
            out.set(key, std::move(v));
            skip_ws();
            if (at(',')) {
                ++p;
                continue;
            }
            if (at('}')) {
                ++p;
                return true;
            }
            return fail("expected , or } in object");
        }
    }
};

void dump_into(const Value& v, std::string& out) {
    char buf[64];
    switch (v.type()) {
        case Value::Type::Null: out += "null"; break;
        case Value::Type::Bool: out += v.as_bool() ? "true" : "false"; break;
        case Value::Type::Number: {
            double d = v.as_number();
            if (d == (double)(int64_t)d && std::fabs(d) < 9.0e15) {
                std::snprintf(buf, sizeof(buf), "%lld", (long long)(int64_t)d);
                out += buf;
            } else {
                std::snprintf(buf, sizeof(buf), "%.17g", d);
                out += buf;
            }
            break;
        }
        case Value::Type::String:
            out += '"';
            out += escape(v.as_string());
            out += '"';
            break;
        case Value::Type::Array: {
            out += '[';
            const Array& a = v.as_array();
            for (size_t i = 0; i < a.size(); ++i) {
                if (i) out += ',';
                dump_into(a[i], out);
            }
            out += ']';
            break;
        }
        case Value::Type::Object: {
            out += '{';
            bool first = true;
            for (const auto& kv : v.as_object()) {
                if (!first) out += ',';
                first = false;
                out += '"';
                out += escape(kv.first);
                out += "\":";
                dump_into(kv.second, out);
            }
            out += '}';
            break;
        }
    }
}

} // namespace

std::string Value::dump() const {
    std::string out;
    out.reserve(256);
    dump_into(*this, out);
    return out;
}

std::string escape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out.push_back((char)c); // UTF-8 bytes pass through
                }
        }
    }
    return out;
}

bool parse(const std::string& text, Value& out, std::string* err) {
    Parser ps;
    ps.p = text.data();
    ps.end = text.data() + text.size();
    out = Value();
    if (!ps.parse_value(out)) {
        if (err) *err = ps.err.empty() ? "parse error" : ps.err;
        return false;
    }
    ps.skip_ws();
    if (ps.p != ps.end) {
        if (err) *err = "trailing characters";
        return false;
    }
    return true;
}

} // namespace cs::json
