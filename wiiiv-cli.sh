#!/usr/bin/env bash
# wiiiv-cli 시작 스크립트
# Usage: ./wiiiv-cli.sh [--build]

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA_HOME="${JAVA_HOME:-C:/Program Files/Eclipse Adoptium/jdk-17.0.17.10-hotspot}"
JAR_PATH="$SCRIPT_DIR/wiiiv-cli/build/libs/wiiiv-cli-2.2.0-SNAPSHOT-all.jar"
BUILD=false

# 인자 파싱
for arg in "$@"; do
    case $arg in
        --build)  BUILD=true; shift ;;
        --help)
            echo "Usage: ./wiiiv-cli.sh [--build]"
            echo "  --build   shadowJar 빌드 후 실행"
            exit 0 ;;
    esac
done

# 빌드
if [ "$BUILD" = true ] || [ ! -f "$JAR_PATH" ]; then
    echo "[wiiiv] Building CLI fat jar..."
    JAVA_HOME="$JAVA_HOME" "$SCRIPT_DIR/gradlew" :wiiiv-cli:shadowJar
fi

exec "$JAVA_HOME/bin/java" \
    -Dfile.encoding=UTF-8 \
    -Dstdout.encoding=UTF-8 \
    -Dstderr.encoding=UTF-8 \
    -jar "$JAR_PATH" "$@"
