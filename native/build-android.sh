#!/usr/bin/env bash
# Cross-compiles the Rust stream proxy for Android.
#
# Usage: native/build-android.sh <output-dir>
# Output: <output-dir>/<abi>/libytdlna.so for arm64-v8a, armeabi-v7a and x86_64.
#
# Needs rustup with the Android targets and the Android NDK:
#   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
set -euo pipefail

out="${1:?usage: $0 <output-dir>}"
crate="$(cd "$(dirname "$0")/ytdlna-proxy" && pwd)"
api=26

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ndk="${ANDROID_NDK_HOME:-$(ls -d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -1)}"
[ -d "$ndk" ] || { echo "Android NDK not found (set ANDROID_NDK_HOME)" >&2; exit 1; }
toolchain=$(ls -d "$ndk"/toolchains/llvm/prebuilt/* | head -1)/bin

# rustup's cargo, so the Android standard libraries are found.
cargo=(cargo)
if command -v rustup >/dev/null; then
  cargo=(rustup run stable cargo)
  # A system rustc (e.g. Homebrew) earlier in PATH lacks the Android targets.
  export RUSTC="$(rustup which --toolchain stable rustc)"
fi

build() {
  local triple=$1 clang=$2 abi=$3
  local env_triple=${triple//-/_}
  local upper=$(echo "$env_triple" | tr '[:lower:]' '[:upper:]')
  export "CC_${env_triple}=$toolchain/${clang}${api}-clang"
  export "AR_${env_triple}=$toolchain/llvm-ar"
  export "CARGO_TARGET_${upper}_LINKER=$toolchain/${clang}${api}-clang"
  # 16 KB page size support (Android 15+ devices).
  export "CARGO_TARGET_${upper}_RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384"
  "${cargo[@]}" build --release --lib --target "$triple" --manifest-path "$crate/Cargo.toml"
  mkdir -p "$out/$abi"
  cp "$crate/target/$triple/release/libytdlna.so" "$out/$abi/libytdlna.so"
}

build aarch64-linux-android aarch64-linux-android arm64-v8a
build armv7-linux-androideabi armv7a-linux-androideabi armeabi-v7a
build x86_64-linux-android x86_64-linux-android x86_64
ls -l "$out"/*/libytdlna.so
