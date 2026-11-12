#!/usr/bin/env bash
# Tests of bump-minor-version.sh. Each case uses its own temporary file.
set -euo pipefail

script="$(dirname "$0")/bump-minor-version.sh"
workDir="$(mktemp -d)"
trap 'rm -rf "$workDir"' EXIT
failures=0

fail() {
    echo "FAIL: $1"
    failures=$((failures + 1))
}

# Usage: expect_bump <case name> <file content> <expected version> <expected file content>
expect_bump() {
    local file="$workDir/$1.gradle.kts"
    printf '%s\n' "$2" >"$file"
    local printed
    if ! printed="$(bash "$script" "$file")"; then
        fail "$1: the script failed"
        return
    fi
    [ "$printed" = "$3" ] || fail "$1: printed '$printed', expected '$3'"
    [ "$(cat "$file")" = "$4" ] || fail "$1: file is '$(cat "$file")', expected '$4'"
    [ ! -e "$file.bak" ] || fail "$1: a backup file is left"
}

# Usage: expect_error <case name> <file content>
expect_error() {
    local file="$workDir/$1.gradle.kts"
    printf '%s\n' "$2" >"$file"
    if bash "$script" "$file" >/dev/null 2>&1; then
        fail "$1: the script must fail"
    fi
    [ "$(cat "$file")" = "$2" ] || fail "$1: the file must not change"
}

expect_bump "minor" 'version = "1.2.0"' "1.3.0" 'version = "1.3.0"'
expect_bump "patch-is-reset" 'version = "1.2.7"' "1.3.0" 'version = "1.3.0"'
expect_bump "minor-of-two-digits" 'version = "1.9.0"' "1.10.0" 'version = "1.10.0"'
expect_bump "minor-with-a-leading-zero" 'version = "2.08.1"' "2.9.0" 'version = "2.9.0"'
expect_bump "major-is-kept" 'version = "12.0.0"' "12.1.0" 'version = "12.1.0"'
# Only the project version changes: a plugin version and an indented version stay as they are.
expect_bump "other-lines-are-kept" \
    'version = "1.2.0"
plugins {
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
    version = "9.9.9"
}' \
    "1.3.0" \
    'version = "1.3.0"
plugins {
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
    version = "9.9.9"
}'

expect_error "no-version" 'group = "com.example"'
expect_error "two-versions" 'version = "1.2.0"
version = "1.3.0"'
expect_error "snapshot-suffix" 'version = "1.2.0-SNAPSHOT"'
expect_error "two-parts" 'version = "1.2"'

if bash "$script" "$workDir/missing.gradle.kts" >/dev/null 2>&1; then
    fail "missing-file: the script must fail"
fi

if [ "$failures" -ne 0 ]; then
    echo "$failures test(s) failed"
    exit 1
fi
echo "all tests passed"
