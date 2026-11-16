@echo off
REM Cross-compiles all native artifacts (run from core/cpp; JAVA_HOME must point to any JDK).
REM Names must stay in sync with core/src/remold/util/NativeLibraryName.java:
REM   tool64.dll           Windows x86_64
REM   libtoolarm64.dylib   macOS arm64
REM   libtool64.dylib      macOS x86_64
REM   libtool64.so         Linux x86_64 (glibc 2.17)
REM   libtoolaarch64.so    Linux aarch64 (glibc 2.17)
REM   libtoolarm64.so      Android arm64 - NOT built here: core/build.gradle compileNativeArm64 / CI NDK (bionic ABI).
set COMMON=-I"%JAVA_HOME%/include" -I"%JAVA_HOME%/include/win32" -std=c++23 -O3 -g0 -fno-exceptions -fno-rtti -fdeclspec -w

zig c++ -shared -target x86_64-windows -Wl,--strip-all %COMMON% -o ../libs/tool64.dll tool.cpp
zig c++ -c -target aarch64-macos %COMMON% -o "%TEMP%\tool_macos_arm64.o" tool.cpp
zig cc -shared -target aarch64-macos -Wl,-dead_strip "%TEMP%\tool_macos_arm64.o" -o ../libs/libtoolarm64.dylib
zig c++ -c -target x86_64-macos %COMMON% -o "%TEMP%\tool_macos_x64.o" tool.cpp
zig cc -shared -target x86_64-macos -Wl,-dead_strip "%TEMP%\tool_macos_x64.o" -o ../libs/libtool64.dylib
zig c++ -c -target x86_64-linux-gnu.2.17 -I"%~dp0jni_posix" %COMMON% -o "%TEMP%\tool_linux_x64.o" tool.cpp
zig cc -shared -target x86_64-linux-gnu.2.17 -Wl,--strip-all "%TEMP%\tool_linux_x64.o" -o ../libs/libtool64.so
REM Linux aarch64: put the posix jni_md.h before %COMMON%. The win32 one (__declspec/__int64)
REM is Windows semantics and must not be used for an ELF artifact.
REM Two-step compile + zig cc link: avoids linking zig's bundled libc++ (which would compile
REM libc++ from source with -Wnullability-completeness warnings) and keeps pthread_mutex_*
REM bound cleanly at GLIBC_2.17 via libc.so.6.
zig c++ -c -target aarch64-linux-gnu.2.17 -I"%~dp0jni_posix" %COMMON% -o "%TEMP%\tool_linux_aarch64.o" tool.cpp
zig cc -shared -target aarch64-linux-gnu.2.17 -Wl,--strip-all "%TEMP%\tool_linux_aarch64.o" -o ../libs/libtoolaarch64.so
