# libsmb2（vendored 头文件 + 运行时二进制）

SMB2/SMB3 客户端库，ComicShelf 的 NAS 书库（`src/vfs/vfs_smb.cpp`）依赖它。

- **许可证**：LGPL-2.1（见 `LICENCE-LGPL-2.1.txt` / `COPYING`；libdcerpc 部分为 BSD-2-Clause）。
  本仓库以**动态链接**方式使用：头文件在此目录，运行库存放于
  `app/src/main/jniLibs/arm64-v8a/libsmb2.so`（LGPL 允许动态链接使用并支持替换该 .so）。
- **上游**：https://github.com/sahlberg/libsmb2

## 运行时二进制（libsmb2.so）的构建

`app/src/main/jniLibs/arm64-v8a/libsmb2.so` 为 Android arm64-v8a 交叉编译产物，大致步骤：

```bash
# 需要 NDK（r26+）与 autotools；
git clone https://github.com/sahlberg/libsmb2 && cd libsmb2
autoreconf -i
./configure --host=aarch64-linux-android --disable-static --enable-shared \
    CC=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android30-clang
make -j
# 产物 lib/.libs/libsmb2.so → 拷贝为 app/src/main/jniLibs/arm64-v8a/libsmb2.so
```

> 若缺少该 .so，CMake 会打印警告并裁剪 SMB 支持（其余功能不受影响）。
