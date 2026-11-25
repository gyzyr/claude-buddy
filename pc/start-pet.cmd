@echo off
rem Start mini-Claude desktop pet (includes the phone brain server on port 8787).
cd /d "%~dp0pet"
if not exist node_modules\electron\dist\electron.exe (
  call npm install --no-audit --no-fund
  node node_modules\electron\install.js
)
rem A copy of electron.exe named "Claude Buddy", so Task Manager shows that name.
if not exist "node_modules\electron\dist\Claude Buddy.exe" (
  powershell -NoProfile -ExecutionPolicy Bypass -File make-exe.ps1
)
if exist "node_modules\electron\dist\Claude Buddy.exe" (
  start "" "node_modules\electron\dist\Claude Buddy.exe" .
) else (
  start "" node_modules\electron\dist\electron.exe .
)
