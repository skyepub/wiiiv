@echo off
REM wiiiv-server 시작 스크립트
REM Usage: start-server.bat [--build] [--port PORT]

setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot
set JAR_PATH=%SCRIPT_DIR%wiiiv-backend\wiiiv-server\build\libs\wiiiv-server-2.2.0-SNAPSHOT-all.jar
set PORT=8235
set BUILD=false

:parse_args
if "%~1"=="" goto :check_build
if "%~1"=="--build" (set BUILD=true & shift & goto :parse_args)
if "%~1"=="--port" (set PORT=%~2 & shift & shift & goto :parse_args)
if "%~1"=="--help" (
    echo Usage: start-server.bat [--build] [--port PORT]
    echo   --build   shadowJar 빌드 후 실행
    echo   --port    서버 포트 (기본: 8235^)
    exit /b 0
)
echo Unknown option: %~1
exit /b 1

:check_build
if "%BUILD%"=="true" goto :do_build
if not exist "%JAR_PATH%" goto :do_build
goto :run

:do_build
echo [wiiiv] Building server fat jar...
call "%SCRIPT_DIR%gradlew.bat" :wiiiv-server:shadowJar
if errorlevel 1 exit /b 1

:run
echo [wiiiv] Starting wiiiv-server on port %PORT%...
"%JAVA_HOME%\bin\java" -Dfile.encoding=UTF-8 -jar "%JAR_PATH%"
