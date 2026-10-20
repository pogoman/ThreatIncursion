@echo off
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-siege-40.ps1" > "%~dp0run-siege-40.out" 2>&1
