#pragma once
// Minimal JSON (parse + serialize) for the local translation protocol.
// No third-party dependency: the protocol payloads are small and simple.
#include <cstddef>
#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace cs::json {

class Value;

using Object = std::map<std::string, Value>;
using Array = std::vector<Value>;

class Value {
public:
    enum class Type { Null, Bool, Number, String, Array, Object };

    Value() = default;
    Value(std::nullptr_t) {}
    Value(bool b) : type_(Type::Bool), b_(b) {}
    Value(int i) : type_(Type::Number), num_((double)i) {}
    Value(int64_t i) : type_(Type::Number), num_((double)i) {}
    Value(double d) : type_(Type::Number), num_(d) {}
    Value(const char* s) : type_(Type::String), str_(s ? s : "") {}
    Value(std::string s) : type_(Type::String), str_(std::move(s)) {}

    static Value make_array() {
        Value v;
        v.type_ = Type::Array;
        return v;
    }
    static Value make_object() {
        Value v;
        v.type_ = Type::Object;
        return v;
    }

    Type type() const { return type_; }
    bool is_null() const { return type_ == Type::Null; }
    bool is_bool() const { return type_ == Type::Bool; }
    bool is_number() const { return type_ == Type::Number; }
    bool is_string() const { return type_ == Type::String; }
    bool is_array() const { return type_ == Type::Array; }
    bool is_object() const { return type_ == Type::Object; }

    bool as_bool(bool def = false) const { return type_ == Type::Bool ? b_ : def; }
    double as_number(double def = 0.0) const { return type_ == Type::Number ? num_ : def; }
    int64_t as_int(int64_t def = 0) const {
        return type_ == Type::Number ? (int64_t)num_ : def;
    }
    // Returns "" when not a string.
    const std::string& as_string() const {
        static const std::string kEmpty;
        return type_ == Type::String ? str_ : kEmpty;
    }
    const Array& as_array() const {
        static const Array kEmpty;
        return type_ == Type::Array ? arr_ : kEmpty;
    }
    const Object& as_object() const {
        static const Object kEmpty;
        return type_ == Type::Object ? obj_ : kEmpty;
    }

    // Object access; returns a shared Null when the key is missing.
    const Value& operator[](const std::string& key) const {
        static const Value kNull;
        if (type_ != Type::Object) return kNull;
        auto it = obj_.find(key);
        return it == obj_.end() ? kNull : it->second;
    }

    // Mutation helpers.
    void set(const std::string& key, Value v) {
        type_ = Type::Object;
        obj_[key] = std::move(v);
    }
    void push_back(Value v) {
        type_ = Type::Array;
        arr_.push_back(std::move(v));
    }
    Array& array() {
        type_ = Type::Array;
        return arr_;
    }
    Object& object() {
        type_ = Type::Object;
        return obj_;
    }

    std::string dump() const;

private:
    Type type_ = Type::Null;
    bool b_ = false;
    double num_ = 0.0;
    std::string str_;
    Array arr_;
    Object obj_;
};

// Parses `text`; on failure returns false and (optionally) sets `err`.
bool parse(const std::string& text, Value& out, std::string* err = nullptr);

// Escapes a string for embedding inside JSON quotes (no surrounding quotes).
std::string escape(const std::string& s);

} // namespace cs::json
