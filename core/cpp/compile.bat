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
zig c++ -shared -target aarch64-macos -Wl,-dead_strip %COMMON% -o ../libs/libtoolarm64.dylib tool.cpp
zig c++ -shared -target x86_64-macos -Wl,-dead_strip %COMMON% -o ../libs/libtool64.dylib tool.cpp
zig c++ -shared -target x86_64-linux-gnu.2.17 -Wl,--strip-all %COMMON% -o ../libs/libtool64.so tool.cpp
REM Linux aarch64: put the posix jni_md.h before %COMMON%. The win32 one (__declspec/__int64)
REM is Windows semantics and must not be used for an ELF artifact.
REM NOTE: the link phase of "zig c++" builds zig's bundled libc++ from source; that internal build
REM ignores -w and may print -Wnullability-completeness warnings from libc++ headers. They are
REM third-party header noise, not from tool.cpp. CI does not rebuild this artifact (it is committed
REM like the macOS/Windows binaries); see .github/workflows/ci.yml "Verify native ABIs".
zig c++ -shared -target aarch64-linux-gnu.2.17 -Wl,--strip-all -I"%~dp0jni_posix" %COMMON% -o ../libs/libtoolaarch64.so tool.cpp
