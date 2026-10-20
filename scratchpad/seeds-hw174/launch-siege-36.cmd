@echo off
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-siege-36.ps1" > "%~dp0run-siege-36.out" 2>&1
