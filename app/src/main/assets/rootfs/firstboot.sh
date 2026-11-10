#!/bin/sh
# ANDRAX-NG first login: remove packages built for another CPU architecture (x86), which cannot run here.
LOG=/root/andrax-ng-firstboot.log
command -v dpkg >/dev/null 2>&1 || exit 0
NATIVE=$(dpkg --print-architecture 2>/dev/null)
[ -n "$NATIVE" ] || exit 0
{
  echo "native architecture: $NATIVE"
  PKGS=$(dpkg-query -W -f='${Package}:${Architecture}\n' 2>/dev/null | grep -E ':(amd64|i386|x86_64)$' || true)
  if [ -n "$PKGS" ]; then
    echo "removing foreign x86 packages:"
    echo "$PKGS"
    dpkg --remove --force-depends $PKGS
  else
    echo "no x86 packages installed"
  fi
  for a in amd64 i386; do
    dpkg --remove-architecture "$a" 2>/dev/null
  done
} >> "$LOG" 2>&1
