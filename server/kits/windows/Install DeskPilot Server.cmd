@echo off
title DeskPilot Server setup
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1"
if errorlevel 1 (
  echo.
  echo   Setup did not finish. See the message above, then run this file again.
)
echo.
pause
