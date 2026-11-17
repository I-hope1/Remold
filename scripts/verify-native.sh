#!/usr/bin/env bash
set -euo pipefail

# 验证 core/libs/ 下全部 6 个原生库的架构、ABI 基线、动态依赖与导出符号。
# 供 ci.yml 与 release.yml 共用。
# 用法：
#   bash scripts/verify-native.sh [dir]                  # 校验指定目录（默认 core/libs）
#   bash scripts/verify-native.sh --compare <dirA> <dirB> # 校验两目录并比对导出符号/minos/依赖防漂移

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

verify_one_dir() {
  local libs_dir="$1"

  for lib in \
    libtool64.so \
    libtoolaarch64.so \
    libtoolarm64.so \
    libtool64.dylib \
    libtoolarm64.dylib \
    tool64.dll; do
    if [ ! -s "$libs_dir/$lib" ]; then
      echo "::error::Missing or empty native library: $libs_dir/$lib" >&2
      exit 1
    fi
  done

  # 1. ELF 验证（Linux x64 / Linux aarch64 glibc 2.17 / Android arm64 bionic）
  readelf -h "$libs_dir/libtool64.so" | grep -F 'X86-64'
  readelf -d "$libs_dir/libtool64.so" | grep -F 'libc.so.6'

  readelf -h "$libs_dir/libtoolaarch64.so" | grep -F 'AArch64'
  readelf -d "$libs_dir/libtoolaarch64.so" | grep -F 'libc.so.6'
  if readelf -V "$libs_dir/libtoolaarch64.so" | grep -E 'GLIBC_2\.(1[89]|[2-9][0-9])'; then
    echo "::error::libtoolaarch64.so requires glibc > 2.17" >&2
    exit 1
  fi

  readelf -h "$libs_dir/libtoolarm64.so" | grep -F 'AArch64'
  readelf -d "$libs_dir/libtoolarm64.so" | grep -F '[libc.so]'

  # 2. Mach-O 与 PE 结构、依赖及导出符号验证
  python3 - "$libs_dir" <<'PY'
import pathlib, struct, sys

libs_dir = pathlib.Path(sys.argv[1])

def inspect_macho(path: pathlib.Path, expected_cpu: int, expected_minos: str, require_codesig: bool):
    b = path.read_bytes()
    magic, cpu, _sub, ft, ncmds, _scmds, _flags, _res = struct.unpack_from("<8I", b, 0)
    assert magic == 0xFEEDFACF, f"{path.name}: bad Mach-O magic {hex(magic)}"
    assert cpu == expected_cpu, f"{path.name}: unexpected cputype {hex(cpu)} != {hex(expected_cpu)}"
    assert ft == 6, f"{path.name}: unexpected filetype {ft} (expected MH_DYLIB=6)"

    dylibs = []
    minos = None
    has_codesig = False
    exports = set()

    off = 32
    for _ in range(ncmds):
        cmd, cmdsize = struct.unpack_from("<2I", b, off)
        if cmd == 0xC:  # LC_LOAD_DYLIB
            name_off = struct.unpack_from("<I", b, off + 8)[0]
            end = b.index(0, off + name_off)
            dylibs.append(b[off + name_off:end].decode("ascii"))
        elif cmd == 0x32:  # LC_BUILD_VERSION
            _plat, v, _sdk, _ntools = struct.unpack_from("<4I", b, off + 8)
            minos = f"{v >> 16}.{(v >> 8) & 0xFF}.{v & 0xFF}"
        elif cmd == 0x24:  # LC_VERSION_MIN_MACOSX
            v, _sdk = struct.unpack_from("<2I", b, off + 8)
            minos = f"{v >> 16}.{(v >> 8) & 0xFF}.{v & 0xFF}"
        elif cmd == 0x1D:  # LC_CODE_SIGNATURE
            has_codesig = True
        elif cmd == 0x2:  # LC_SYMTAB
            symoff, nsyms, stroff, strsize = struct.unpack_from("<4I", b, off + 8)
            strtab = b[stroff:stroff + strsize]
            for idx in range(nsyms):
                n_strx, n_type, _n_sect, _n_desc, _n_val = struct.unpack_from("<IBBHQ", b, symoff + idx * 16)
                # N_EXT (0x01) + N_SECT (0x0E) => defined exported symbol
                if (n_type & 0x0F) == 0x0F and n_strx < len(strtab):
                    end = strtab.index(0, n_strx)
                    exports.add(strtab[n_strx:end].decode("ascii", errors="replace"))
        off += cmdsize

    print(f"[verify] {path.name}: minos={minos}, dylibs={dylibs}, codesig={has_codesig}, exports={len(exports)}")
    assert minos == expected_minos, f"{path.name}: minos={minos} != {expected_minos}"
    assert dylibs == ["/usr/lib/libSystem.B.dylib"], f"{path.name}: unexpected dylibs {dylibs}"
    if require_codesig:
        assert has_codesig, f"{path.name}: missing LC_CODE_SIGNATURE (required on Apple Silicon)"
    for sym in ("_JNI_OnLoad", "_JNI_OnUnload", "_Java_remold_util_LibTool_nEnumBucketWindow", "_Java_remold_util_LibTool_nEnumBucketCount"):
        assert sym in exports, f"{path.name}: exported symbol {sym} missing"

def inspect_pe_dll(path: pathlib.Path):
    b = path.read_bytes()
    assert b[:2] == b"MZ", f"{path.name}: missing MZ header"
    pe_off = struct.unpack_from("<I", b, 0x3C)[0]
    assert b[pe_off:pe_off + 4] == b"PE\x00\x00", f"{path.name}: missing PE signature"
    machine, nsects, _ts, _psym, _nsym, opt_size, chars = struct.unpack_from("<HHIIIHH", b, pe_off + 4)
    assert machine == 0x8664, f"{path.name}: unexpected machine {hex(machine)} (expected AMD64=0x8664)"
    assert (chars & 0x2000) != 0, f"{path.name}: IMAGE_FILE_DLL flag not set"

    opt_off = pe_off + 24
    opt_magic = struct.unpack_from("<H", b, opt_off)[0]
    assert opt_magic == 0x20B, f"{path.name}: expected PE32+ optional header (0x20B), got {hex(opt_magic)}"

    exp_rva, _exp_sz = struct.unpack_from("<2I", b, opt_off + 112)
    imp_rva, _imp_sz = struct.unpack_from("<2I", b, opt_off + 120)

    sect_off = opt_off + opt_size
    sections = []
    for i in range(nsects):
        _name, vsize, va, rsize, raw_ptr = struct.unpack_from("<8s4I", b, sect_off + i * 40)
        sections.append((va, max(vsize, rsize), raw_ptr))

    def rva_to_off(rva: int) -> int:
        for va, span, raw_ptr in sections:
            if va <= rva < va + span:
                return raw_ptr + (rva - va)
        raise AssertionError(f"{path.name}: unmapped RVA {hex(rva)}")

    def read_cstr(off: int) -> str:
        end = b.index(0, off)
        return b[off:end].decode("ascii")

    assert exp_rva != 0, f"{path.name}: missing export directory"
    eo = rva_to_off(exp_rva)
    _eflags, _ets, _maj, _min, _name_rva, _base, _nfuncs, nnames, _af, an, _ao = struct.unpack_from("<2I2H7I", b, eo)
    names_off = rva_to_off(an)
    exports = {read_cstr(rva_to_off(struct.unpack_from("<I", b, names_off + 4 * i)[0])) for i in range(nnames)}

    imports = []
    if imp_rva != 0:
        io = rva_to_off(imp_rva)
        while True:
            oft, _ts, _fc, name_rva, ft = struct.unpack_from("<5I", b, io)
            if oft == 0 and name_rva == 0 and ft == 0:
                break
            imports.append(read_cstr(rva_to_off(name_rva)))
            io += 20

    print(f"[verify] {path.name}: exports={sorted(exports)}, imports={imports}")
    for sym in ("JNI_OnLoad", "JNI_OnUnload", "Java_remold_util_LibTool_nEnumBucketWindow", "Java_remold_util_LibTool_nEnumBucketCount"):
        assert sym in exports, f"{path.name}: missing PE export {sym}"
    for imp in imports:
        low = imp.lower()
        assert low == "kernel32.dll" or low.startswith("api-ms-win-crt-"), f"{path.name}: unexpected runtime DLL import {imp}"

inspect_macho(libs_dir / "libtool64.dylib", 0x01000007, "10.13.0", require_codesig=False)
inspect_macho(libs_dir / "libtoolarm64.dylib", 0x0100000C, "11.0.0", require_codesig=True)
inspect_pe_dll(libs_dir / "tool64.dll")
PY
}

compare_dirs() {
  local dir_a="$1"
  local dir_b="$2"

  echo "[compare] Checking repo-committed binaries ($dir_a) against fresh build ($dir_b)"
  for so in libtool64.so libtoolaarch64.so libtoolarm64.so; do
    diff -u \
      <(readelf --dyn-syms -W "$dir_a/$so" | awk '$4=="FUNC" && $7!="UND" {print $8}' | sort -u) \
      <(readelf --dyn-syms -W "$dir_b/$so" | awk '$4=="FUNC" && $7!="UND" {print $8}' | sort -u)
    diff -u \
      <(readelf -d "$dir_a/$so" | grep '(NEEDED)' | sort -u) \
      <(readelf -d "$dir_b/$so" | grep '(NEEDED)' | sort -u)
  done

  python3 - "$dir_a" "$dir_b" <<'PY'
import pathlib, struct, sys

dir_a = pathlib.Path(sys.argv[1])
dir_b = pathlib.Path(sys.argv[2])

def macho_signature(path: pathlib.Path):
    b = path.read_bytes()
    _m, cpu, _s, _ft, ncmds, _sc, _fl, _r = struct.unpack_from("<8I", b, 0)
    dylibs, minos, has_sig, exports = [], None, False, set()
    off = 32
    for _ in range(ncmds):
        cmd, cmdsize = struct.unpack_from("<2I", b, off)
        if cmd == 0xC:
            no = struct.unpack_from("<I", b, off + 8)[0]
            dylibs.append(b[off + no:b.index(0, off + no)].decode("ascii"))
        elif cmd == 0x32:
            _p, v, _sdk, _nt = struct.unpack_from("<4I", b, off + 8)
            minos = f"{v >> 16}.{(v >> 8) & 0xFF}.{v & 0xFF}"
        elif cmd == 0x24:
            v, _sdk = struct.unpack_from("<2I", b, off + 8)
            minos = f"{v >> 16}.{(v >> 8) & 0xFF}.{v & 0xFF}"
        elif cmd == 0x1D:
            has_sig = True
        elif cmd == 0x2:
            symoff, nsyms, stroff, strsize = struct.unpack_from("<4I", b, off + 8)
            strtab = b[stroff:stroff + strsize]
            for idx in range(nsyms):
                n_strx, n_type, _ns, _nd, _nv = struct.unpack_from("<IBBHQ", b, symoff + idx * 16)
                if (n_type & 0x0F) == 0x0F and n_strx < len(strtab):
                    exports.add(strtab[n_strx:strtab.index(0, n_strx)].decode("ascii", errors="replace"))
        off += cmdsize
    return (cpu, minos, tuple(dylibs), has_sig, sorted(exports))

def pe_signature(path: pathlib.Path):
    b = path.read_bytes()
    pe_off = struct.unpack_from("<I", b, 0x3C)[0]
    machine, nsects, _t, _ps, _ns, opt_size, _c = struct.unpack_from("<HHIIIHH", b, pe_off + 4)
    opt_off = pe_off + 24
    exp_rva, _ = struct.unpack_from("<2I", b, opt_off + 112)
    imp_rva, _ = struct.unpack_from("<2I", b, opt_off + 120)
    sect_off = opt_off + opt_size
    sections = [struct.unpack_from("<8s4I", b, sect_off + i * 40)[1:] for i in range(nsects)]
    def rva_to_off(rva: int) -> int:
        for vsize, va, rsize, raw_ptr in sections:
            if va <= rva < va + max(vsize, rsize):
                return raw_ptr + (rva - va)
        raise AssertionError(f"{path.name}: unmapped RVA {hex(rva)}")
    def read_cstr(off: int) -> str:
        return b[off:b.index(0, off)].decode("ascii")
    eo = rva_to_off(exp_rva)
    nnames = struct.unpack_from("<2I2H7I", b, eo)[7]
    an = struct.unpack_from("<2I2H7I", b, eo)[9]
    names_off = rva_to_off(an)
    exports = sorted(read_cstr(rva_to_off(struct.unpack_from("<I", b, names_off + 4 * i)[0])) for i in range(nnames))
    imports = []
    if imp_rva != 0:
        io = rva_to_off(imp_rva)
        while True:
            oft, _ts, _fc, name_rva, ft = struct.unpack_from("<5I", b, io)
            if oft == 0 and name_rva == 0 and ft == 0:
                break
            imports.append(read_cstr(rva_to_off(name_rva)).lower())
            io += 20
    return (machine, exports, sorted(imports))

for name in ("libtool64.dylib", "libtoolarm64.dylib"):
    assert macho_signature(dir_a / name) == macho_signature(dir_b / name), f"{name}: checked-in binary drifted from fresh build"
assert pe_signature(dir_a / "tool64.dll") == pe_signature(dir_b / "tool64.dll"), "tool64.dll: checked-in binary drifted from fresh build"
print("[compare] Checked-in core/libs binaries match fresh CI build (symbols/minos/deps).")
PY
}

if [ "${1:-}" = "--compare" ]; then
  if [ "$#" -ne 3 ]; then
    echo "Usage: $0 --compare <checked_in_dir> <fresh_dir>" >&2
    exit 2
  fi
  verify_one_dir "$2"
  verify_one_dir "$3"
  compare_dirs "$2" "$3"
else
  TARGET_DIR="${1:-$REPO_ROOT/core/libs}"
  verify_one_dir "$TARGET_DIR"
fi

echo "[verify] All 6 native libraries verified successfully."
