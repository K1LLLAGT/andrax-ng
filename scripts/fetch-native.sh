#!/usr/bin/env bash
# Fetches proot + helpers from the official Termux package repo, verifies the
# SHA-256 from the repo index, and installs them as jniLibs for arm64-v8a.
# Works in Termux (pkg install curl binutils tar) and on Ubuntu CI runners.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=app/src/main/jniLibs/arm64-v8a
BASE=https://packages.termux.dev/apt/termux-main
INDEX=$BASE/dists/stable/main/binary-aarch64/Packages
PFX=data/data/com.termux/files/usr

for c in curl ar tar sha256sum awk install; do
  command -v "$c" >/dev/null || { echo "missing tool: $c"; exit 1; }
done

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "[*] downloading package index"
curl -fsSL "$INDEX" -o "$WORK/Packages"

field() { # field <package> <field name>
  awk -v p="$1" -v f="$2" 'BEGIN{RS="";FS="\n"}
    $1=="Package: "p { for(i=1;i<=NF;i++) if (index($i, f": ")==1) { print substr($i, length(f)+3); exit } }' \
    "$WORK/Packages"
}

fetch_pkg() {
  local p=$1 fn sum
  fn=$(field "$p" Filename)
  sum=$(field "$p" SHA256)
  [ -n "$fn" ] && [ -n "$sum" ] || { echo "package $p not found in index"; exit 1; }
  echo "[*] $p  ($fn)"
  curl -fsSL "$BASE/$fn" -o "$WORK/$p.deb"
  echo "$sum  $WORK/$p.deb" | sha256sum -c - >/dev/null \
    || { echo "SHA-256 mismatch for $p"; exit 1; }
  mkdir -p "$WORK/x_$p"
  ( cd "$WORK/x_$p" && ar x "../$p.deb" && tar -xf data.tar.xz )
  echo "$p $(field "$p" Version) $sum" >> "$WORK/sources.txt"
}

fetch_pkg proot
fetch_pkg libtalloc
fetch_pkg libandroid-shmem

mkdir -p "$OUT"
install -m 0755 "$WORK/x_proot/$PFX/bin/proot"                  "$OUT/libproot.so"
install -m 0755 "$WORK/x_proot/$PFX/libexec/proot/loader"       "$OUT/libproot-loader.so"
install -m 0755 "$WORK/x_proot/$PFX/libexec/proot/loader32"     "$OUT/libproot-loader32.so"
# cp -L follows the libtalloc.so -> libtalloc.so.2 -> libtalloc.so.2.5.0 chain.
cp -L "$WORK/x_libtalloc/$PFX/lib/libtalloc.so"                 "$OUT/libtalloc.so"
chmod 0755 "$OUT/libtalloc.so"
install -m 0755 "$WORK/x_libandroid-shmem/$PFX/lib/libandroid-shmem.so" "$OUT/libandroid-shmem.so"

cp "$WORK/sources.txt" app/native-sources.txt
echo
echo "[+] installed to $OUT:"
ls -l "$OUT"
echo
echo "[+] recorded in app/native-sources.txt:"
cat app/native-sources.txt
