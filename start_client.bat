@echo off
REM Double-click to PLAY: compiles the game and opens the window.
REM Players only JOIN here - the arena is created by start_server.bat (start that first, on one computer).
cd /d "%~dp0"
call compile.bat
if errorlevel 1 exit /b 1

java -cp out QuizClient
if errorlevel 1 pause
