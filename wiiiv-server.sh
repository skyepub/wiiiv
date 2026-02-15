#!/usr/bin/env bash
# wiiiv-server 시작 스크립트
# Usage: ./start-server.sh [--build] [--port PORT]

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA_HOME="${JAVA_HOME:-C:/Program Files/Eclipse Adoptium/jdk-17.0.17.10-hotspot}"
JAR_PATH="$SCRIPT_DIR/wiiiv-backend/wiiiv-server/build/libs/wiiiv-server-2.2.0-SNAPSHOT-all.jar"
PORT=8235
BUILD=false

# 인자 파싱
while [[ $# -gt 0 ]]; do
    case $1 in
        --build)  BUILD=true; shift ;;
        --port)   PORT="$2"; shift 2 ;;
        --help)
            echo "Usage: ./start-server.sh [--build] [--port PORT]"
            echo "  --build   shadowJar 빌드 후 실행"
            echo "  --port    서버 포트 (기본: 8235)"
            exit 0 ;;
        *)  echo "Unknown option: $1"; exit 1 ;;
    esac
done

# 빌드
if [ "$BUILD" = true ] || [ ! -f "$JAR_PATH" ]; then
    echo "[wiiiv] Building server fat jar..."
    JAVA_HOME="$JAVA_HOME" "$SCRIPT_DIR/gradlew" :wiiiv-server:shadowJar
fi

echo "[wiiiv] Starting wiiiv-server on port $PORT..."
exec "$JAVA_HOME/bin/java" \
    -Dfile.encoding=UTF-8 \
    -jar "$JAR_PATH"
