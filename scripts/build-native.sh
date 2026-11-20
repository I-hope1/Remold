#!/usr/bin/env bash
set -euo pipefail

# 统一跨平台 native 库构建脚本（供 ci.yml 与 release.yml 共用，保证构建参数同源）。
# 用法：
#   bash scripts/build-native.sh               # 构建全部 6 个平台产物到 core/libs/
#   bash scripts/build-native.sh linux-x64     # 仅构建指定目标（linux-x64|android-arm64|linux-aarch64|macos-x64|macos-arm64|windows-x64）
#   bash scripts/build-native.sh all-parallel  # 6 个目标并行（NATIVE_JOBS 控制并发度，默认 nproc）
#   bash scripts/build-native.sh prepare       # 只解包 zig，不编译（并行前置）

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CPP_DIR="$REPO_ROOT/core/cpp"
TOOL_CPP="$CPP_DIR/tool.cpp"
OUT_DIR="$REPO_ROOT/core/libs"

ZIG_VERSION="${ZIG_VERSION:-0.17.1}"
ZIG_CACHE_DIR="${ZIG_CACHE_DIR:-/tmp/remold-zig-${ZIG_VERSION}}"

if [ -z "${JAVA_HOME:-}" ]; then
  echo "::error::JAVA_HOME must be set for JNI/JVMTI headers" >&2
  exit 1
fi
if [ ! -f "$JAVA_HOME/include/jni.h" ] || [ ! -f "$JAVA_HOME/include/jvmti.h" ]; then
  echo "::error::JAVA_HOME ($JAVA_HOME) missing include/jni.h or include/jvmti.h" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

ZIG_CMD=""
ensure_zig() {
  if [ -n "$ZIG_CMD" ]; then
    return 0
  fi
  if [ -n "${ZIG_BIN:-}" ]; then
    ZIG_CMD="$ZIG_BIN"
    return 0
  fi
  local host_os host_arch zig_arch tarball url unpacked
  host_os="$(uname -s)"
  host_arch="$(uname -m)"
  if [ "$host_os" != "Linux" ]; then
    if command -v zig >/dev/null 2>&1; then
      ZIG_CMD="$(command -v zig)"
      return 0
    fi
    echo "::error::zig not found on $host_os (install zig $ZIG_VERSION or set ZIG_BIN)" >&2
    exit 1
  fi
  case "$host_arch" in
    x86_64|amd64) zig_arch="x86_64" ;;
    aarch64|arm64) zig_arch="aarch64" ;;
    *)
      echo "::error::Unsupported Linux host architecture for zig download: $host_arch" >&2
      exit 1
      ;;
  esac
  unpacked="$ZIG_CACHE_DIR/zig-${zig_arch}-linux-${ZIG_VERSION}"
  if [ ! -x "$unpacked/zig" ]; then
    mkdir -p "$ZIG_CACHE_DIR"
    tarball="$WORK_DIR/zig.tar.xz"
    url="https://ziglang.org/download/${ZIG_VERSION}/zig-${zig_arch}-linux-${ZIG_VERSION}.tar.xz"
    echo "Downloading zig ${ZIG_VERSION} from ${url}"
    curl -fsSL "$url" -o "$tarball"
    tar -xJf "$tarball" -C "$ZIG_CACHE_DIR"
  fi
  ZIG_CMD="$unpacked/zig"
  echo "Using zig: $ZIG_CMD ($("$ZIG_CMD" version))"
}

# linux-x64 也走 zig（不再是宿主 g++）：三个理由
# 1) 产物的 NEEDED / glibc 版本floor 必须与本机复核结果一致，否则"仓内产物 vs CI 新构建"的
#    同步校验没法在非 x86_64 开发机上复现（aarch64 机器上跑不了 g++ 的 x86_64 目标）；
# 2) 与其余 POSIX 目标共用同一套两段式编译/链接参数，避免各目标之间悄悄分叉；
# 3) 显式钉住 glibc 2.17 下限，比"宿主发行版的 glibc"更保守。
build_linux_x64() {
  build_zig_posix_two_step "linux-x64" "x86_64-linux-gnu.2.17" \
    "-Wl,--strip-all" "$WORK_DIR/tool_linux_x64.o" "$OUT_DIR/libtool64.so"
}

# Android 产物的编译器选择顺序：
#   1) ANDROID_CXX —— 显式指定的交叉编译器（例如在 aarch64 宿主上用本机 clang 驱动 r27c sysroot
#      的 wrapper；NDK 自带的 prebuilt/linux-x86_64 里的 clang 在 aarch64 宿主上无法执行）；
#   2) NDK 自带的 aarch64-linux-android<API>-clang++。
# 两条路径都必须产出与前缀一致的导出符号与 NEEDED（libm/libdl/libc）——verify-native.sh --compare 会盯住。
build_android_arm64() {
  if [ -n "${ANDROID_CXX:-}" ]; then
    if [ ! -x "$ANDROID_CXX" ]; then
      echo "::error::ANDROID_CXX is set but not executable: $ANDROID_CXX" >&2
      exit 1
    fi
    echo "[native] Building libtoolarm64.so (android-arm64 via $ANDROID_CXX, ANDROID_CXX override)"
    "$ANDROID_CXX" -shared -fPIC -static-libstdc++ -std=c++2b -O3 \
      -I"$CPP_DIR/jni_posix" \
      -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
      "$TOOL_CPP" -o "$OUT_DIR/libtoolarm64.so"
    ls -la "$OUT_DIR/libtoolarm64.so"
    return 0
  fi
  local ndk="${ANDROID_NDK_HOME:-}"
  if [ -z "$ndk" ] && [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT/ndk" ]; then
    ndk="$(find "$ANDROID_SDK_ROOT/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
  fi
  if [ -z "$ndk" ] && [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    ndk="$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
  fi
  if [ -z "$ndk" ] || [ ! -d "$ndk" ]; then
    echo "::error::ANDROID_NDK_HOME not found" >&2
    exit 1
  fi
  local compiler="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android30-clang++"
  if [ ! -x "$compiler" ]; then
    echo "::error::Android NDK compiler not executable: $compiler" >&2
    exit 1
  fi
  echo "[native] Building libtoolarm64.so (android-arm64 via $ndk)"
  "$compiler" -shared -fPIC -static-libstdc++ -std=c++2b -O3 \
    -I"$CPP_DIR/jni_posix" \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    "$TOOL_CPP" -o "$OUT_DIR/libtoolarm64.so"
  ls -la "$OUT_DIR/libtoolarm64.so"
}

build_zig_posix_two_step() {
  local label="$1"
  local target="$2"
  local link_flag="$3"
  local obj_file="$4"
  local out_file="$5"

  ensure_zig
  echo "[native] Building $(basename "$out_file") ($label, target=$target via zig)"
  # 两段式编译（zig c++ -c）+ 链接（zig c++ -shared）。
  # 链接必须用 **c++ 驱动**：tool.cpp 已经用到 std::vector / std::expected，目标文件里会出现
  # operator new/delete、__libcpp_verbose_abort 这类 C++ 运行时引用；`zig cc` 只按 C 驱动链接，
  # 这些符号在 macOS dylib / 静态运行时目标上会直接报 undefined symbol。
  # 用 c++ 驱动后 zig 会把内置 libc++ 静态链进产物，产物因此不再依赖宿主的 libstdc++。
  "$ZIG_CMD" c++ -c -target "$target" \
    -std=c++23 -O3 -g0 -fno-exceptions -fno-rtti -fdeclspec \
    -I"$CPP_DIR/jni_posix" -I"$JAVA_HOME/include" \
    "$TOOL_CPP" -o "$obj_file"
  "$ZIG_CMD" c++ -shared -target "$target" \
    "$link_flag" "$obj_file" -o "$out_file"
  ls -la "$out_file"
}

build_linux_aarch64() {
  build_zig_posix_two_step "linux-aarch64" "aarch64-linux-gnu.2.17" \
    "-Wl,--strip-all" "$WORK_DIR/tool_linux_aarch64.o" "$OUT_DIR/libtoolaarch64.so"
}

build_macos_x64() {
  build_zig_posix_two_step "macos-x64" "x86_64-macos.10.13" \
    "-Wl,-dead_strip" "$WORK_DIR/tool_macos_x64.o" "$OUT_DIR/libtool64.dylib"
}

build_macos_arm64() {
  build_zig_posix_two_step "macos-arm64" "aarch64-macos.11.0" \
    "-Wl,-dead_strip" "$WORK_DIR/tool_macos_arm64.o" "$OUT_DIR/libtoolarm64.dylib"
}

build_windows_x64() {
  ensure_zig
  echo "[native] Building tool64.dll (windows-x64, target=x86_64-windows-gnu via zig)"
  "$ZIG_CMD" c++ -shared -target x86_64-windows-gnu -Wl,--strip-all \
    -std=c++23 -O3 -g0 -fno-exceptions -fno-rtti -fdeclspec -w \
    -I"$CPP_DIR/jni_win32" -I"$JAVA_HOME/include" \
    "$TOOL_CPP" -o "$OUT_DIR/tool64.dll"
  # zig 编出 DLL 时会在同目录留下 import lib（tool.lib），避免污染打包进 jar 的 libs/。
  rm -f "$OUT_DIR/tool.lib" "$OUT_DIR/tool64.lib" "$OUT_DIR/libtool64.dll.a"
  ls -la "$OUT_DIR/tool64.dll"
}

ALL_TARGETS=(linux-x64 android-arm64 linux-aarch64 macos-x64 macos-arm64 windows-x64)

# 只把 zig 解包好、不编译：并行构建前必须先把这一步串行做掉，否则多个子进程会同时
# 下载同一个 tarball 到同一个目录（互相截断）。
if [ "$#" -ge 1 ] && [ "$1" = "prepare" ]; then
  ensure_zig
  echo "[native] zig ready: $ZIG_CMD"
  exit 0
fi

# 并行构建：每个目标一个子进程（各自 mktemp 工作目录、各自输出文件，互不覆盖）。
# 串行时 6 个目标 ≈ 3.5 分钟，主要成本是把同一份 tool.cpp 按 6 个 target 各编译一遍。
if [ "$#" -ge 1 ] && { [ "$1" = "all-parallel" ] || [ "$1" = "parallel" ]; }; then
  ensure_zig
  jobs="${NATIVE_JOBS:-0}"
  if [ "$jobs" -le 0 ] 2>/dev/null; then
    jobs="$( (command -v nproc >/dev/null 2>&1 && nproc) || echo 4 )"
  fi
  echo "[native] building ${#ALL_TARGETS[@]} targets with up to $jobs in parallel"
  pids=()
  for t in "${ALL_TARGETS[@]}"; do
    while [ "$(jobs -rp | wc -l)" -ge "$jobs" ]; do wait -n 2>/dev/null || true; done
    bash "${BASH_SOURCE[0]}" "$t" &
    pids+=($!)
  done
  failed=0
  for p in "${pids[@]}"; do
    if ! wait "$p"; then failed=1; fi
  done
  if [ "$failed" -ne 0 ]; then
    echo "::error::one or more native targets failed" >&2
    exit 1
  fi
  echo "[native] all ${#ALL_TARGETS[@]} targets built"
  exit 0
fi

if [ "$#" -eq 0 ] || [ "$1" = "all" ]; then
  TARGETS=("${ALL_TARGETS[@]}")
else
  TARGETS=("$@")
fi

for t in "${TARGETS[@]}"; do
  case "$t" in
    linux-x64)     build_linux_x64 ;;
    android-arm64) build_android_arm64 ;;
    linux-aarch64) build_linux_aarch64 ;;
    macos-x64)     build_macos_x64 ;;
    macos-arm64)   build_macos_arm64 ;;
    windows-x64)   build_windows_x64 ;;
    *)
      echo "::error::Unknown native build target: $t" >&2
      exit 1
      ;;
  esac
done
