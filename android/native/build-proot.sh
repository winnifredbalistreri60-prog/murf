#!/usr/bin/env bash
# Сборка proot (форк termux/proot) + talloc для встроенного локального режима MURF.
# Результат: android/app/src/main/jniLibs/<abi>/{libproot.so,libproot-loader.so,libproot-loader32.so}
# talloc линкуется статически (отдельный libtalloc.so не нужен).
# Требования: Android NDK r27+ (ANDROID_NDK_HOME), python3, make, curl, git.
# Все бинарники выравниваются на 16 КБ (Android 15+ с 16K страницами).
set -euo pipefail

PROOT_REPO="${PROOT_REPO:-https://github.com/termux/proot}"
PROOT_REF="${PROOT_REF:-52d65a4d}"        # termux/proot master, 2026-11-01 (5.1.107.102+)
PROOT_VERSION="${PROOT_VERSION:-5.1.107.102-murf}"
TALLOC_VERSION="${TALLOC_VERSION:-2.5.0}"
TALLOC_SHA256="912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007"
API="${ANDROID_API:-26}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64}"

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${OUT:-$HERE/../app/src/main/jniLibs}"
WORK="${WORK:-$HERE/build}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
[ -n "$NDK" ] || NDK="$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/ndk/2[7-9]* 2>/dev/null | sort -V | tail -1)"
[ -d "$NDK" ] || { echo "Нужен NDK r27+: задайте ANDROID_NDK_HOME" >&2; exit 1; }
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="$NDK/prebuilt/linux-x86_64/bin:$TC/bin:$PATH"   # make из NDK
PAGE_LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"

mkdir -p "$WORK" "$OUT" "$WORK/bin"
# loader/loader-info.awk использует gawk strtonum; если gawk нет, подменяем awk для этого шага на python
if ! awk 'BEGIN{exit strtonum("0x10")!=16}' 2>/dev/null; then
  cat > "$WORK/bin/awk" <<'AWK'
#!/bin/sh
case "$*" in
  *loader-info.awk*) exec python3 -c '
import re,sys
v={}
for l in sys.stdin:
    f=l.split()
    for k in ("pokedata_workaround","_start"):
        if re.search(r"\b%s\b"%k,l) and len(f)>1: v[k]=int(f[1],16)
print("#include <unistd.h>")
print("const ssize_t offset_to_pokedata_workaround=%d;"%(v.get("pokedata_workaround",0)-v.get("_start",0)))' ;;
  *) for a in /usr/bin/awk /bin/awk; do [ -x "$a" ] && exec "$a" "$@"; done ;;
esac
AWK
  chmod +x "$WORK/bin/awk"; export PATH="$WORK/bin:$PATH"
fi
cd "$WORK"
[ -d proot-src ] || git clone -q "$PROOT_REPO" proot-src
git -C proot-src fetch -q origin 2>/dev/null || true
git -C proot-src checkout -q "$PROOT_REF"
if [ ! -f "talloc-$TALLOC_VERSION.tar.gz" ]; then
  curl -fsSLO "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz"
fi
echo "$TALLOC_SHA256  talloc-$TALLOC_VERSION.tar.gz" | sha256sum -c -

triple() {
  case "$1" in
    arm64-v8a) echo aarch64-linux-android ;;
    armeabi-v7a) echo armv7a-linux-androideabi ;;
    x86_64) echo x86_64-linux-android ;;
    x86) echo i686-linux-android ;;
    *) echo "unknown ABI $1" >&2; exit 1 ;;
  esac
}

build_talloc() { # $1 abi, $2 prefix
  local abi=$1 prefix=$2 t; t=$(triple "$abi")
  rm -rf "talloc-$abi" && mkdir "talloc-$abi"
  tar -xzf "talloc-$TALLOC_VERSION.tar.gz" -C "talloc-$abi" --strip-components=1
  ( cd "talloc-$abi"
    cat > cross-answers.txt <<'XA'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
XA
    CC="$TC/bin/$t$API-clang" AR="$TC/bin/llvm-ar" LDFLAGS="$PAGE_LDFLAGS" \
      ./configure --prefix="$prefix" --disable-rpath --disable-python \
      --cross-compile --cross-answers=cross-answers.txt >/dev/null
    make -j"$(nproc)" >/dev/null
    mkdir -p "$prefix/lib" "$prefix/include"
    "$TC/bin/llvm-ar" rcs "$prefix/lib/libtalloc.a" bin/default/talloc.c.5.o bin/default/lib/replace/replace.c.1.o bin/default/lib/replace/closefrom.c.1.o
    cp talloc.h "$prefix/include/" )
}

for abi in $ABIS; do
  t=$(triple "$abi"); prefix="$WORK/prefix-$abi"
  echo "== $abi ($t, API $API)"
  build_talloc "$abi" "$prefix"
  rm -rf "proot-$abi" && cp -a proot-src "proot-$abi"
  # флаги через окружение: в GNUmakefile они дополняются через +=
  CPPFLAGS="-I$prefix/include -DARG_MAX=131072 -DVERSION=\\\"$PROOT_VERSION\\\"" \
  CFLAGS="-I$prefix/include -Wno-error=implicit-function-declaration -Wno-unused-parameter" \
  LDFLAGS="-L$prefix/lib $PAGE_LDFLAGS" \
  LOADER_LDFLAGS="$PAGE_LDFLAGS" \
  PROOT_UNBUNDLE_LOADER=/murf-loader-from-env \
  env "LOADER_LDFLAGS-m32=$PAGE_LDFLAGS" make -C "proot-$abi/src" -j"$(nproc)" \
    CC="$TC/bin/$t$API-clang" \
    STRIP="$TC/bin/llvm-strip" OBJCOPY="$TC/bin/llvm-objcopy" OBJDUMP="$TC/bin/llvm-objdump" \
    proot >"$WORK/make-$abi.log" 2>&1 || { tail -30 "$WORK/make-$abi.log"; exit 1; }
  mkdir -p "$OUT/$abi"
  "$TC/bin/llvm-strip" -o "$OUT/$abi/libproot.so" "proot-$abi/src/proot"
  "$TC/bin/llvm-strip" -o "$OUT/$abi/libproot-loader.so" "proot-$abi/src/loader/loader"
  if [ -f "proot-$abi/src/loader/loader-m32" ]; then
    "$TC/bin/llvm-strip" -o "$OUT/$abi/libproot-loader32.so" "proot-$abi/src/loader/loader-m32"
  fi
  chmod 755 "$OUT/$abi"/lib*.so
  # проверка: talloc статически, выравнивание LOAD-сегментов >= 16 КБ
  if "$TC/bin/llvm-readelf" -d "$OUT/$abi/libproot.so" | grep -q 'libtalloc'; then echo "ОШИБКА: talloc слинкован динамически" >&2; exit 1; fi
  for f in "$OUT/$abi"/lib*.so; do
    "$TC/bin/llvm-readelf" -lW "$f" | python3 -c 'import sys
bad=[l for l in sys.stdin if l.split()[:1]==["LOAD"] and int(l.split()[-1],16)<16384]
sys.exit("ОШИБКА выравнивания: "+sys.argv[1] if bad else 0)' "$f"
  done
  ls -l "$OUT/$abi"
done
echo "Готово."
