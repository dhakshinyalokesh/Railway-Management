@echo off
cd /d "%~dp0"
where mvn >nul 2>nul
if errorlevel 1 (
  echo Apache Maven is required to download the SQLite driver and run this app.
  echo Install Maven, reopen this window, and run run-web.bat again.
  pause
  exit /b 1
)
mvn -q compile exec:java
