#!/usr/bin/env bash
# Raises the minor version in a Gradle build file and prints the new version.
# The file must hold exactly one line of the form: version = "MAJOR.MINOR.PATCH"
# Example: 1.2.3 becomes 1.3.0.
set -euo pipefail

file="${1:-build.gradle.kts}"

current="$(sed -n 's/^version = "\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)"$/\1/p' "$file")"
if [ "$(printf '%s' "$current" | grep -c '')" -ne 1 ]; then
    echo "error: expected exactly one line 'version = \"MAJOR.MINOR.PATCH\"' in $file" >&2
    exit 1
fi

IFS=. read -r major minor _ <<<"$current"
# 10# reads the number as decimal, also with a leading zero.
next="$major.$((10#$minor + 1)).0"

sed -i.bak "s/^version = \"$current\"\$/version = \"$next\"/" "$file"
rm "$file.bak"

echo "$next"
