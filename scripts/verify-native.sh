#!/usr/bin/env bash
set -euo pipefail

# 验证 core/libs/ 下全部 6 个原生库的架构、ABI 基线、动态依赖与导出符号。
# 供 ci.yml 与 release.yml 共用。

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
LIBS_DIR="$REPO_ROOT/core/libs"

for lib in \
  libtool64.so \
  libtoolaarch64.so \
  libtoolarm64.so \
  libtool64.dylib \
  libtoolarm64.dylib \
  tool64.dll; do
  if [ ! -s "$LIBS_DIR/$lib" ]; then
    echo "::error::Missing or empty native library: $LIBS_DIR/$lib" >&2
    exit 1
  fi
done

# 1. ELF 验证（Linux x64 / Linux aarch64 glibc 2.17 / Android arm64 bionic）
readelf -h "$LIBS_DIR/libtool64.so" | grep -F 'X86-64'
readelf -d "$LIBS_DIR/libtool64.so" | grep -F 'libc.so.6'

readelf -h "$LIBS_DIR/libtoolaarch64.so" | grep -F 'AArch64'
readelf -d "$LIBS_DIR/libtoolaarch64.so" | grep -F 'libc.so.6'
if readelf -V "$LIBS_DIR/libtoolaarch64.so" | grep -E 'GLIBC_2\.(1[89]|[2-9][0-9])'; then
  echo "::error::libtoolaarch64.so requires glibc > 2.17" >&2
  exit 1
fi

readelf -h "$LIBS_DIR/libtoolarm64.so" | grep -F 'AArch64'
readelf -d "$LIBS_DIR/libtoolarm64.so" | grep -F '[libc.so]'

# 2. Mach-O 与 PE 结构、依赖及导出符号验证
python3 - "$LIBS_DIR" <<'PY'
import pathlib, struct, sys

libs_dir = pathlib.Path(sys.argv[1])

def check_macho(filename: str, expected_cpu: int, expected_minos: str, require_codesig: bool):
    b = (libs_dir / filename).read_bytes()
    magic, cpu, _sub, ft, ncmds, _scmds, _flags, _res = struct.unpack_from("<8I", b, 0)
    assert magic == 0xFEEDFACF, f"{filename}: bad Mach-O magic {hex(magic)}"
    assert cpu == expected_cpu, f"{filename}: unexpected cputype {hex(cpu)} != {hex(expected_cpu)}"
    assert ft == 6, f"{filename}: unexpected filetype {ft} (expected MH_DYLIB=6)"

    dylibs = []
    minos = None
    has_codesig = False
    has_symtab = False
    strtab = b""

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
            has_symtab = True
            _symoff, _nsyms, stroff, strsize = struct.unpack_from("<4I", b, off + 8)
            strtab = b[stroff:stroff + strsize]
        off += cmdsize

    print(f"[verify] {filename}: minos={minos}, dylibs={dylibs}, codesig={has_codesig}")
    assert minos == expected_minos, f"{filename}: minos={minos} != {expected_minos}"
    assert dylibs == ["/usr/lib/libSystem.B.dylib"], f"{filename}: unexpected dylibs {dylibs}"
    if require_codesig:
        assert has_codesig, f"{filename}: missing LC_CODE_SIGNATURE (required on Apple Silicon)"
    assert has_symtab, f"{filename}: missing LC_SYMTAB"
    for sym in (b"JNI_OnLoad", b"JNI_OnUnload", b"Java_remold_util_LibTool_nEnumBucketWindow", b"Java_remold_util_LibTool_nEnumBucketCount"):
        assert sym in strtab, f"{filename}: symbol {sym.decode()} missing from string table"

def check_pe_dll(filename: str):
    b = (libs_dir / filename).read_bytes()
    assert b[:2] == b"MZ", f"{filename}: missing MZ header"
    pe_off = struct.unpack_from("<I", b, 0x3C)[0]
    assert b[pe_off:pe_off + 4] == b"PE\x00\x00", f"{filename}: missing PE signature"
    machine, nsects, _ts, _psym, _nsym, opt_size, chars = struct.unpack_from("<HHIIIHH", b, pe_off + 4)
    assert machine == 0x8664, f"{filename}: unexpected machine {hex(machine)} (expected AMD64=0x8664)"
    assert (chars & 0x2000) != 0, f"{filename}: IMAGE_FILE_DLL flag not set"

    opt_off = pe_off + 24
    opt_magic = struct.unpack_from("<H", b, opt_off)[0]
    assert opt_magic == 0x20B, f"{filename}: expected PE32+ optional header (0x20B), got {hex(opt_magic)}"

    # PE32+ DataDirectory starts at opt_off + 112 (index 0: Export, index 1: Import)
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
        raise AssertionError(f"{filename}: unmapped RVA {hex(rva)}")

    def read_cstr(off: int) -> str:
        end = b.index(0, off)
        return b[off:end].decode("ascii")

    assert exp_rva != 0, f"{filename}: missing export directory"
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

    print(f"[verify] {filename}: exports={sorted(exports)}, imports={imports}")
    for sym in ("JNI_OnLoad", "JNI_OnUnload", "Java_remold_util_LibTool_nEnumBucketWindow", "Java_remold_util_LibTool_nEnumBucketCount"):
        assert sym in exports, f"{filename}: missing PE export {sym}"
    for imp in imports:
        low = imp.lower()
        assert low == "kernel32.dll" or low.startswith("api-ms-win-crt-"), f"{filename}: unexpected runtime DLL import {imp}"

check_macho("libtool64.dylib", 0x01000007, "10.13.0", require_codesig=False)
check_macho("libtoolarm64.dylib", 0x0100000C, "11.0.0", require_codesig=True)
check_pe_dll("tool64.dll")
PY

echo "[verify] All 6 native libraries verified successfully."
