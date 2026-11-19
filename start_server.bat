@echo off
REM Double-click this FIRST (on one computer) to create the arena.
REM It asks for the number of players and questions. Then everybody starts start_client.bat to join.
cd /d "%~dp0"
call compile.bat
if errorlevel 1 exit /b 1

java -cp out QuizServer
pause
