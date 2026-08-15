#!/usr/bin/env bash
set -euo pipefail

readonly go_version='go1.26.6'
readonly archive_url='https://github.com/MetaCubeX/go/releases/download/build/go1.26.linux-amd64.tar.gz'
readonly archive_sha256='b5cd93b9b05e96d952ee20c449306ff9ff16849393e4b484ee6165401916451a'
readonly archive="$RUNNER_TEMP/metacubex-go.tar.gz"
readonly install_root="$RUNNER_TEMP/metacubex-go"
readonly goroot="$install_root/go"

curl --fail --location --proto '=https' --tlsv1.2 --retry 3 --output "$archive" "$archive_url"
printf '%s  %s\n' "$archive_sha256" "$archive" | sha256sum --check --status || {
  echo '::error::MetaCubeX Go archive checksum mismatch'
  exit 1
}

rm -rf "$install_root"
mkdir -p "$install_root"
tar --extract --gzip --file "$archive" --directory "$install_root" --strip-components=1
rm -f "$archive"

actual=$("$goroot/bin/go" version)
[[ "$actual" == "go version $go_version linux/amd64" ]] || {
  echo "::error::Unexpected Go toolchain version: $actual"
  exit 1
}

printf 'GOROOT=%s\n' "$goroot" >> "$GITHUB_ENV"
printf 'GOTOOLCHAIN=local\n' >> "$GITHUB_ENV"
printf '%s/bin\n' "$goroot" >> "$GITHUB_PATH"
