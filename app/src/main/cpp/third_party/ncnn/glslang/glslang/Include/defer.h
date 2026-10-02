//
// Copyright 2026 Google LLC
//
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions
// are met:
//
//    Redistributions of source code must retain the above copyright
//    notice, this list of conditions and the following disclaimer.
//
//    Redistributions in binary form must reproduce the above
//    copyright notice, this list of conditions and the following
//    disclaimer in the documentation and/or other materials provided
//    with the distribution.
//
//    Neither the name of 3Dlabs Inc. Ltd. nor the names of its
//    contributors may be used to endorse or promote products derived
//    from this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
// FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
// COPYRIGHT HOLDERS OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
// INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
// BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
// LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
// CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
// LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
// ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
// POSSIBILITY OF SUCH DAMAGE.
//

#ifndef GLSLANG_INCLUDE_DEFER_H
#define GLSLANG_INCLUDE_DEFER_H

#include <type_traits>
#include <utility>

namespace glslang {

// An object that, when destroyed, executes a given function.
// Use this to perform work along all exit paths from a function.
template <typename F>
class Defer {
 public:
  explicit Defer(F f) : f_(std::move(f)), active_(true) { }
  Defer(Defer&& other) : f_(std::move(other.f_)), active_(other.active_) { other.active_ = false; }
  ~Defer() { if (active_) f_(); } // Run the given function.
 private:
  Defer(const Defer&) = delete;
  Defer& operator=(const Defer&) = delete;
  F f_;
  bool active_;
};

template <typename F>
Defer<typename std::decay<F>::type> makeDefer(F&& f)
{
  return Defer<typename std::decay<F>::type>(std::forward<F>(f));
}

#if __cplusplus >= 201703L
// Template argument deduction guide for Defer.
template <typename T>
Defer(T) -> Defer<T>;
#endif

} // namespace glslang

#endif
