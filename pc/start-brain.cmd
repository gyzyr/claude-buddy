@echo off
rem Start the phone brain server (mini-Claude). Window stays open with the log.
chcp 65001 >nul
cd /d "%~dp0"
set "ADB=adb"
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if defined ANDROID_HOME set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
"%ADB%" reverse tcp:8787 tcp:8787 >nul 2>&1
node brain-server.mjs
pause
