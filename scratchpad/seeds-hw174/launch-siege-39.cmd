@echo off
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-siege-39.ps1" > "%~dp0run-siege-39.out" 2>&1
