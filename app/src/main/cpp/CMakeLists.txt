# ComicShelf for Android — native core.
#
# Native core: SQLite metadata, incremental scanner, zip/rar/folder archives,
# stb/WebP/AVIF decode chain, three-level cover cache, translation engine —
# all behind a JNI bridge.
#
# 可选依赖（缺省即自动裁剪，不影响"局域网后端翻译"主线）：
#   ocr/qnn_inc/      高通 QNN SDK 头（端侧 NPU OCR；SDK 不可再分发，需自备）
#   ../deps/llama.cpp llama.cpp 源码（端侧离线 GGUF 翻译）
#   third_party/libsmb2/include + jniLibs/libsmb2.so  SMB/NAS 支持（仓库自带）
cmake_minimum_required(VERSION 3.22)
project(comicshelf LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Our own sources compile clean; third-party trees stay quiet.
add_compile_options(-Wno-unused-parameter)

set(TP "${CMAKE_CURRENT_SOURCE_DIR}/third_party")
set(SRC "${CMAKE_CURRENT_SOURCE_DIR}/src")

if(NOT EXISTS "${TP}/sqlite/sqlite3.c")
    message(FATAL_ERROR "Missing third-party sources under ${TP}")
endif()

# ---------------------------------------------------------------- sqlite3
add_library(cs_sqlite3 STATIC "${TP}/sqlite/sqlite3.c")
target_include_directories(cs_sqlite3 PUBLIC "${TP}/sqlite")
target_compile_definitions(cs_sqlite3 PRIVATE
    SQLITE_ENABLE_FTS5 SQLITE_ENABLE_JSON1 SQLITE_THREADSAFE=1
    SQLITE_DEFAULT_MEMSTATUS=0 SQLITE_OMIT_DEPRECATED
    SQLITE_TEMP_STORE=2 SQLITE_OS_UNIX)

# ---------------------------------------------------------------- miniz (zip)
add_library(cs_miniz STATIC
    "${TP}/miniz/miniz.c"
    "${TP}/miniz/miniz_tdef.c"
    "${TP}/miniz/miniz_tinfl.c"
    "${TP}/miniz/miniz_zip.c")
target_include_directories(cs_miniz PUBLIC "${TP}/miniz")

# ---------------------------------------------------------------- stb (headers)
add_library(cs_stb INTERFACE)
target_include_directories(cs_stb INTERFACE "${TP}/stb")

# ---------------------------------------------------------------- unrar
set(CS_HAS_RAR OFF)
if(EXISTS "${TP}/unrar/rar.hpp" AND EXISTS "${TP}/unrar/dll.hpp")
    file(GLOB UNRAR_SOURCES "${TP}/unrar/*.cpp")
    set(UNRAR_EXCLUDE
        arccmt.cpp
        isnt.cpp motw.cpp
        blake2s_sse.cpp blake2sp.cpp
        cmdfilter.cpp cmdmix.cpp
        log.cpp
        crypt1.cpp crypt2.cpp crypt3.cpp crypt5.cpp
        hardlinks.cpp win32stm.cpp win32acl.cpp win32lnk.cpp uowners.cpp ulinks.cpp
        recvol3.cpp recvol5.cpp
        threadmisc.cpp
        uicommon.cpp uisilent.cpp uiconsole.cpp
        coder.cpp suballoc.cpp model.cpp unpackinline.cpp unpack50mt.cpp
        unpack15.cpp unpack20.cpp unpack30.cpp unpack50.cpp unpack50frag.cpp)
    set(UNRAR_FILTERED "")
    foreach(src ${UNRAR_SOURCES})
        get_filename_component(fn "${src}" NAME)
        list(FIND UNRAR_EXCLUDE "${fn}" _idx)
        if(_idx EQUAL -1)
            list(APPEND UNRAR_FILTERED "${src}")
        endif()
    endforeach()
    add_library(cs_unrar STATIC ${UNRAR_FILTERED})
    target_include_directories(cs_unrar PUBLIC "${TP}/unrar")
    # _UNIX selects the POSIX code paths inside the UnRAR sources. PUBLIC:
    # our archive_rar.cpp includes dll.hpp too.
    target_compile_definitions(cs_unrar PUBLIC RARDLL UNRAR _UNIX NOVOLUME)
    target_compile_options(cs_unrar PRIVATE -w -fexceptions -frtti)
    set(CS_HAS_RAR ON)
endif()

# ---------------------------------------------------------------- libwebp
set(CS_HAS_WEBP OFF)
if(EXISTS "${TP}/libwebp/CMakeLists.txt")
    set(WEBP_BUILD_ANIM_UTILS OFF CACHE BOOL "" FORCE)
    set(WEBP_BUILD_CWEBP     OFF CACHE BOOL "" FORCE)
    set(WEBP_BUILD_DWEBP     OFF CACHE BOOL "" FORCE)
    set(WEBP_BUILD_GIF2WEBP  OFF CACHE BOOL "" FORCE)
    set(WEBP_BUILD_IMG2WEBP  OFF CACHE BOOL "" FORCE)
    set(WEBP_BUILD_VWEBP     OFF CACHE BOOL "" FORCE)
    set(WEBP_BUILD_WEBPINFO  OFF CACHE BOOL "" FORCE)
    set(WEBP_BUILD_WEBPMUX   OFF CACHE BOOL "" FORCE)
    set(WEBP_BUILD_EXTRAS    OFF CACHE BOOL "" FORCE)
    set(WEBP_LINK_STATIC     ON  CACHE BOOL "" FORCE)
    add_subdirectory("${TP}/libwebp" "${CMAKE_BINARY_DIR}/libwebp" EXCLUDE_FROM_ALL)
    set(CS_HAS_WEBP ON)
endif()

# ---------------------------------------------------------------- libavif + libaom
set(CS_HAS_AVIF OFF)
if(EXISTS "${TP}/libavif/CMakeLists.txt" AND EXISTS "${TP}/aom/CMakeLists.txt")
    set(AVIF_CODEC_AOM         "LOCAL" CACHE STRING "" FORCE)
    set(AVIF_CODEC_AOM_DECODE  ON      CACHE BOOL   "" FORCE)
    set(AVIF_CODEC_AOM_ENCODE  OFF     CACHE BOOL   "" FORCE)
    set(AVIF_CODEC_DAV1D       "OFF"   CACHE STRING "" FORCE)
    set(AVIF_CODEC_LIBGAV1     "OFF"   CACHE STRING "" FORCE)
    set(AVIF_CODEC_RAV1E       "OFF"   CACHE STRING "" FORCE)
    set(AVIF_CODEC_SVT         "OFF"   CACHE STRING "" FORCE)
    set(AVIF_CODEC_AVM         "OFF"   CACHE STRING "" FORCE)
    set(AVIF_LIBYUV            "OFF"   CACHE STRING "" FORCE)
    set(AVIF_LIBSHARPYUV       "OFF"   CACHE STRING "" FORCE)
    set(AVIF_BUILD_APPS        OFF     CACHE BOOL   "" FORCE)
    set(AVIF_BUILD_EXAMPLES    OFF     CACHE BOOL   "" FORCE)
    set(AVIF_BUILD_TESTS       OFF     CACHE BOOL   "" FORCE)
    set(AVIF_ENABLE_WERROR     OFF     CACHE BOOL   "" FORCE)
    set(FETCHCONTENT_SOURCE_DIR_LIBAOM "${TP}/aom" CACHE PATH "" FORCE)
    set(FETCHCONTENT_SOURCE_DIR_AOM    "${TP}/aom" CACHE PATH "" FORCE)

    # aom: decoder only. On arm64 the intrinsics (NEON) need no external
    # assembler, so let CMake detect the cpu and enable NEON paths.
    set(CONFIG_AV1_DECODER 1 CACHE STRING "" FORCE)
    set(CONFIG_AV1_ENCODER 0 CACHE STRING "" FORCE)
    set(ENABLE_TESTS       0 CACHE STRING "" FORCE)
    set(ENABLE_EXAMPLES    0 CACHE STRING "" FORCE)
    set(ENABLE_TOOLS       0 CACHE STRING "" FORCE)
    set(ENABLE_DOCS        0 CACHE STRING "" FORCE)
    set(ENABLE_NASM        0 CACHE STRING "" FORCE)
    set(ENABLE_SVE         0 CACHE STRING "" FORCE)

    set(CMAKE_POLICY_VERSION_MINIMUM 3.5)
    add_subdirectory("${TP}/libavif" "${CMAKE_BINARY_DIR}/libavif" EXCLUDE_FROM_ALL)

    set(CS_AOM_BINARY_DIR "${CMAKE_BINARY_DIR}/_deps/libaom-build")
    foreach(_t avif_obj avif)
        if(TARGET ${_t})
            target_include_directories(${_t} PRIVATE
                "$<BUILD_INTERFACE:${CS_AOM_BINARY_DIR}>"
                "$<BUILD_INTERFACE:${CS_AOM_BINARY_DIR}/config>")
        endif()
    endforeach()
    set(CS_HAS_AVIF ON)
endif()

# ---------------------------------------------------------------- ncnn (GPU inference)
set(CS_HAS_NCNN OFF)
if(EXISTS "${TP}/ncnn/src/CMakeLists.txt")
    set(NCNN_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
    set(NCNN_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(NCNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
    set(NCNN_SHARED_LIB     OFF CACHE BOOL "" FORCE)
    set(NCNN_OPENMP         OFF CACHE BOOL "" FORCE)   # NDK has no libomp
    set(NCNN_VULKAN         ON  CACHE BOOL "" FORCE)   # Adreno 740 GPU
    set(NCNN_BUILD_INT8     ON  CACHE BOOL "" FORCE)
    set(NCNN_DISABLE_RTTI   OFF CACHE BOOL "" FORCE)
    set(NCNN_STRING_KERNELLENS OFF CACHE BOOL "" FORCE)
    add_subdirectory("${TP}/ncnn" "${CMAKE_BINARY_DIR}/ncnn" EXCLUDE_FROM_ALL)
    set(CS_HAS_NCNN ON)
endif()

# ---------------------------------------------------------------- cs_core
set(CS_CORE_SOURCES
    ${SRC}/util/path_util.cpp
    ${SRC}/util/thread_pool.cpp
    ${SRC}/util/logger.cpp
    ${SRC}/util/crash_handler.cpp
    ${SRC}/util/json_min.cpp
    ${SRC}/core/database.cpp
    ${SRC}/core/library.cpp
    ${SRC}/core/scanner.cpp
    ${SRC}/core/auto_sync.cpp
    ${SRC}/core/settings.cpp
    ${SRC}/vfs/vfs.cpp
    ${SRC}/vfs/vfs_local.cpp
    ${SRC}/vfs/vfs_smb.cpp
    ${SRC}/vfs/byte_source.cpp
    ${SRC}/archive/archive.cpp
    ${SRC}/archive/archive_zip.cpp
    ${SRC}/archive/archive_rar.cpp
    ${SRC}/image/image_util.cpp
    ${SRC}/image/image_util_webp.cpp
    ${SRC}/image/image_util_avif.cpp
    ${SRC}/image/thumbnail_cache.cpp
    ${SRC}/translate/http_client.cpp
    ${SRC}/translate/translate_client.cpp
    ${SRC}/translate/translate_engine.cpp
    ${SRC}/translate/llm_local.cpp)

add_library(cs_core STATIC ${CS_CORE_SOURCES})
target_include_directories(cs_core PUBLIC "${SRC}")
target_link_libraries(cs_core PUBLIC cs_sqlite3 cs_miniz cs_stb log)

# ---------------------------------------------------------------- 图像解码后端
# 注意：libwebp / libavif 的 add_subdirectory 只“编库”，启用必须同时
#   target_compile_definitions（宏——源码里是 #if CS_HAS_WEBP 判断）
#   + target_link_libraries（符号）
# 少任何一半，image_util_webp.cpp / image_util_avif.cpp 都会走
# “永远返回 false”的桩——Android 上所有 webp/avif 图会静默解码失败。
if(CS_HAS_WEBP)
    # webpdemux：动画 webp 取首帧（封面/预览）用
    target_link_libraries(cs_core PUBLIC webp webpdemux)
    target_compile_definitions(cs_core PUBLIC CS_HAS_WEBP=1)
else()
    target_compile_definitions(cs_core PUBLIC CS_HAS_WEBP=0)
endif()
if(CS_HAS_AVIF)
    target_link_libraries(cs_core PUBLIC avif)
    target_compile_definitions(cs_core PUBLIC CS_HAS_AVIF=1)
else()
    target_compile_definitions(cs_core PUBLIC CS_HAS_AVIF=0)
endif()

# ---------------------------------------------------------------- libsmb2 (SMB2)
# libsmb2 (LGPL-2.1): 头文件 vendored 在 third_party/libsmb2/include，
# 运行时 .so 由 APK 的 jniLibs/arm64-v8a/libsmb2.so 提供（均在仓库内）。
set(CS_SMB2_INC "${TP}/libsmb2/include"
    CACHE PATH "libsmb2 头文件目录")
set(CS_SMB2_LIB "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a/libsmb2.so"
    CACHE FILEPATH "libsmb2 .so 路径")
set(CS_HAS_SMB2 OFF)
if(EXISTS "${CS_SMB2_INC}/smb2/libsmb2.h" AND EXISTS "${CS_SMB2_LIB}")
    add_library(cs_smb2 SHARED IMPORTED)
    set_target_properties(cs_smb2 PROPERTIES
        IMPORTED_LOCATION "${CS_SMB2_LIB}")
    target_include_directories(cs_smb2 INTERFACE "${CS_SMB2_INC}")
    set(CS_HAS_SMB2 ON)
    message(STATUS "libsmb2: ${CS_SMB2_LIB}")
else()
    message(WARNING "libsmb2 缺失，SMB 支持将被裁剪（vfs_smb 走空实现）")
endif()

if(CS_HAS_SMB2)
    target_link_libraries(cs_core PUBLIC cs_smb2)
    target_compile_definitions(cs_core PUBLIC CS_HAS_SMB2=1)
else()
    target_compile_definitions(cs_core PUBLIC CS_HAS_SMB2=0)
endif()

# ---------------------------------------------------------------- llama.cpp (可选：端侧离线翻译)
# 作为子项目静态编入 libcomicshelf.so（单一 so，无版本错配；编译标志可控）。
# 可选依赖：仓库不含 llama.cpp 源码时自动裁剪端侧离线翻译（局域网后端模式不受影响）。
# 启用方式：把 llama.cpp 源码放到 <repo 上级>/deps/llama.cpp，或 -DCS_LLAMA_SRC=<路径>。
set(CS_LLAMA_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../deps/llama.cpp"
    CACHE PATH "llama.cpp 源码目录（可选）")
if(EXISTS "${CS_LLAMA_SRC}/include/llama.h")
    set(LLAMA_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS   OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER  OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL          OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP         OFF CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE      OFF CACHE BOOL "" FORCE)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)
    endif()
    add_subdirectory("${CS_LLAMA_SRC}" "${CMAKE_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)
    set(CS_HAS_LLAMA ON)
    message(STATUS "llama.cpp: 静态编入 (${GGML_CPU_ARM_ARCH})")
else()
    set(CS_HAS_LLAMA OFF)
    message(WARNING "llama.cpp 源码缺失（可选）：端侧离线翻译裁剪，局域网后端模式不受影响")
endif()

if(CS_HAS_LLAMA)
    target_link_libraries(cs_core PUBLIC llama ggml)
    target_include_directories(cs_core PUBLIC
        "${CS_LLAMA_SRC}/include" "${CS_LLAMA_SRC}/ggml/include")
    target_compile_definitions(cs_core PUBLIC CS_HAS_LLAMA=1)
else()
    target_compile_definitions(cs_core PUBLIC CS_HAS_LLAMA=0)
endif()

# ---------------------------------------------------------------- comicshelf (JNI)
# 端侧 NPU OCR（ocr/ctbd_qnn、qnn_ocr、qnn_vl、paddle_vl + jni/ocr_jni.cpp）依赖高通
# QNN SDK 头文件与**不可再分发**的运行时库。仓库不含 ocr/qnn_inc 时自动裁剪该路径：
# 局域网后端（cs-backend）模式不受影响；如需端侧 NPU OCR，请自备 QNN SDK 放入 ocr/qnn_inc。
set(CS_QNN_INC "${CMAKE_CURRENT_SOURCE_DIR}/ocr/qnn_inc"
    CACHE PATH "QNN SDK 头文件目录（可选，自备）")
set(CS_HAS_QNN OFF)
if(EXISTS "${CS_QNN_INC}/QnnBackend.h")
    set(CS_HAS_QNN ON)
endif()

file(GLOB JNI_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/jni/*.cpp)
if(CS_HAS_QNN)
    file(GLOB OCR_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/ocr/*.cpp)
else()
    set(OCR_SOURCES "")
    list(REMOVE_ITEM JNI_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/jni/ocr_jni.cpp")
    message(WARNING "QNN SDK 头文件缺失(${CS_QNN_INC})：端侧 NPU OCR 路径已裁剪"
                    "（局域网后端模式不受影响）")
endif()
add_library(comicshelf SHARED ${JNI_SOURCES} ${OCR_SOURCES})
target_include_directories(comicshelf PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
if(CS_HAS_QNN)
    target_include_directories(comicshelf PRIVATE
                              "${CS_QNN_INC}" "${CS_QNN_INC}/QNN")
endif()
target_compile_definitions(comicshelf PRIVATE CS_HAS_QNN=$<BOOL:${CS_HAS_QNN}>)
target_link_libraries(comicshelf PRIVATE cs_core jnigraphics log android dl)

if(CS_HAS_NCNN)
    target_link_libraries(comicshelf PRIVATE ncnn)
    target_compile_definitions(comicshelf PRIVATE CS_HAS_NCNN=1)
else()
    target_compile_definitions(comicshelf PRIVATE CS_HAS_NCNN=0)
endif()
