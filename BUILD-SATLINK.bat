@echo off
cd /d "%~dp0"
call gradle :app:assembleDebug --console=plain
if errorlevel 1 exit /b 1
echo APK: app/build/outputs/apk/debug/app-debug.apk
