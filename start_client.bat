@echo off
REM Double-click to compile and PLAY Quiz Battle (the same program hosts a game or joins one).
cd /d "%~dp0"
call compile.bat
if errorlevel 1 exit /b 1

java -cp out QuizClient
if errorlevel 1 pause
