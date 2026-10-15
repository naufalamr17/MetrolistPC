@echo off
set "METROLIST_EXE=%~dp0desktop\build\compose\binaries\main\app\Metrolist Desktop\Metrolist Desktop.exe"
if exist "%METROLIST_EXE%" (
  start "" "%METROLIST_EXE%"
  exit /b
)
echo The Windows application has not been built. Run Build-Desktop.ps1 first.
pause
