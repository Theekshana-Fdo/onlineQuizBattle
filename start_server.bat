@echo off
REM OPTIONAL: a server without a window for playing (for example on a spare laptop).
REM Normally you do NOT need this - "Host a game" in start_client.bat starts the server for you.
cd /d "%~dp0"
call compile.bat
if errorlevel 1 exit /b 1

java -cp out QuizServer
pause
