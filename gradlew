#!/bin/sh
# Gradle wrapper script
# This script downloads and runs Gradle if not already present

# Determine the Java command to use
if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

# Determine the project base directory
BASEDIR=$(dirname "$0")

# Determine the Gradle home directory
GRADLE_USER_HOME=${GRADLE_USER_HOME:-$HOME/.gradle}

# Download Gradle if not present
GRADLE_VERSION=8.2
GRADLE_HOME="$GRADLE_USER_HOME/wrapper/dists/gradle-$GRADLE_VERSION-bin"
GRADLE_ZIP="$GRADLE_HOME/gradle-$GRADLE_VERSION-bin.zip"

if [ ! -d "$GRADLE_HOME/gradle-$GRADLE_VERSION" ]; then
    echo "Downloading Gradle $GRADLE_VERSION..."
    mkdir -p "$GRADLE_HOME"
    curl -L -o "$GRADLE_ZIP" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    unzip -q -o "$GRADLE_ZIP" -d "$GRADLE_HOME"
    rm -f "$GRADLE_ZIP"
fi

# Run Gradle
exec "$GRADLE_HOME/gradle-$GRADLE_VERSION/bin/gradle" "$@"
