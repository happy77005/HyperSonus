@echo off
setlocal

echo ===============================
echo Starting Android Build Process
echo ===============================

REM Change to project directory (Current Working Directory)
cd /d "%~dp0"

echo Running Gradle build...
call gradlew :app:assembleDebug > build_log.txt 2>&1

IF %ERRORLEVEL% NEQ 0 (
    echo.
    echo BUILD FAILED!
    echo Check build_log.txt for details.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo Build successful.

echo Installing APK to device...
adb install -r app\build\outputs\apk\debug\app-debug.apk

IF %ERRORLEVEL% NEQ 0 (
    echo.
    echo APK installation failed.
    echo Check device connection.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo Clearing previous logcat...
adb logcat -c

endlocal