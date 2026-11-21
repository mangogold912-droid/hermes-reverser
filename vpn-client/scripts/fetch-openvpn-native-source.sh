#!/usr/bin/env bash
set -euo pipefail

DEST="${1:-$(cd "$(dirname "$0")/.." && pwd)/native-source/ics-openvpn-v0.7.64}"
EXPECTED="73ce3c99dde39207990d66c21be8201228cd1f09"

if [[ -e "$DEST" ]]; then
  echo "Refusing to overwrite existing path: $DEST" >&2
  exit 2
fi
mkdir -p "$(dirname "$DEST")"
git clone --recurse-submodules --branch v0.7.64 \
  https://github.com/schwabe/ics-openvpn.git "$DEST"
ACTUAL="$(git -C "$DEST" rev-parse HEAD)"
if [[ "$ACTUAL" != "$EXPECTED" ]]; then
  echo "Unexpected ics-openvpn revision: $ACTUAL (expected $EXPECTED)" >&2
  exit 1
fi
git -C "$DEST" submodule update --init --recursive
printf 'OpenVPN for Android source: %s\n' "$ACTUAL"
