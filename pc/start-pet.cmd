@echo off
rem Start mini-Claude desktop pet (includes the phone brain server on port 8787).
cd /d "%~dp0pet"
if not exist node_modules\electron\dist\electron.exe (
  call npm install --no-audit --no-fund
  node node_modules\electron\install.js
)
start "" node_modules\electron\dist\electron.exe .
