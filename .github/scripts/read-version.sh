#!/usr/bin/env bash
# Prints the project version of a Gradle build file.
# The file must hold exactly one line of the form: version = "MAJOR.MINOR.PATCH"
set -euo pipefail

file="${1:-build.gradle.kts}"

current="$(sed -n 's/^version = "\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)"$/\1/p' "$file")"
if [ "$(printf '%s' "$current" | grep -c '')" -ne 1 ]; then
    echo "error: expected exactly one line 'version = \"MAJOR.MINOR.PATCH\"' in $file" >&2
    exit 1
fi

echo "$current"
