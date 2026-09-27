#!/usr/bin/env bash
# build-mac-peer.sh — compile the real macOS transport for the interop tests.
#
# The Android app has no way to talk to a Mac from a unit test, so the actual
# AeroServer.cpp / CertManager.cpp from the macOS project are compiled into a
# small driver. The interop tests then run the real C++ — real TLS 1.3
# configuration, real adler32 header validation — against the Android code.
#
# Nothing in the macOS project is modified; the sources are compiled as-is.
#
#   Usage: tools/build-mac-peer.sh [path-to-AeroDrop-mac-repo]
#
# Requires: clang++, and OpenSSL 3 headers (brew install openssl@3).

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="$here/macpeer"

mac_repo="${1:-$(cd "$here/../.." && pwd)/AeroDrop}"
cxx_src="$mac_repo/AeroDrop/CXX"

if [[ ! -f "$cxx_src/AeroServer.cpp" ]]; then
    echo "error: cannot find $cxx_src/AeroServer.cpp" >&2
    echo "       pass the macOS repo path as the first argument" >&2
    exit 1
fi

# Locate OpenSSL 3 headers: Homebrew first, then pkg-config, then the system.
ssl_prefix=""
for candidate in \
    "$(brew --prefix openssl@3 2>/dev/null || true)" \
    "$(brew --prefix openssl 2>/dev/null || true)" \
    "$(pkg-config --variable=prefix openssl 2>/dev/null || true)"; do
    if [[ -n "$candidate" && -f "$candidate/include/openssl/ssl.h" ]]; then
        ssl_prefix="$candidate"
        break
    fi
done

if [[ -z "$ssl_prefix" ]]; then
    echo "error: no OpenSSL 3 headers found. Install with: brew install openssl@3" >&2
    exit 1
fi

echo "macOS sources : $cxx_src"
echo "OpenSSL       : $ssl_prefix"

clang++ -std=c++17 -O1 \
    -I"$cxx_src" \
    -I"$ssl_prefix/include" \
    "$here/macpeer.cpp" \
    "$cxx_src/AeroServer.cpp" \
    "$cxx_src/CertManager.cpp" \
    -L"$ssl_prefix/lib" -lssl -lcrypto \
    -Wl,-rpath,"$ssl_prefix/lib" \
    -o "$out"

echo "built $out"
