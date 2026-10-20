@echo off
cd /d "%~dp0"
"C:\Program Files\Git\bin\bash.exe" "%~dp0watch-siege-40.sh" > "%~dp0watch-siege-40.out" 2>&1
