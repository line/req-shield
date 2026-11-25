#!/usr/bin/env bash
# Raises the minor version in a Gradle build file and prints the new version.
# read-version.sh states the form of the version line.
# Example: 1.2.3 becomes 1.3.0.
set -euo pipefail

file="${1:-build.gradle.kts}"

current="$(bash "$(dirname "$0")/read-version.sh" "$file")"

IFS=. read -r major minor _ <<<"$current"
# 10# reads the number as decimal, also with a leading zero.
next="$major.$((10#$minor + 1)).0"

sed -i.bak "s/^version = \"$current\"\$/version = \"$next\"/" "$file"
rm "$file.bak"

echo "$next"
