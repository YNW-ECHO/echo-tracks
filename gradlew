#!/bin/sh
# Gradle wrapper stub — CI uses gradle/actions/setup-gradle (no wrapper needed).
# Local: Android Studio will auto-generate gradle-wrapper.jar on first open,
# or run: gradle wrapper --gradle-version 8.7
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -x "$DIR/gradlew.real" ]; then exec "$DIR/gradlew.real" "$@"; fi
if command -v gradle >/dev/null 2>&1; then exec gradle "$@"; fi
echo "Gradle not found. Install Gradle 8.7 or open this folder in Android Studio (it generates the wrapper)." >&2
exit 1
