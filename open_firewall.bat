@echo off
REM Run this ONCE on the computer that runs the SERVER (right-click -> Run as administrator).
REM It lets other computers reach the game: TCP 5000 (the game) and UDP 5001 (the automatic search).
REM (Normally Windows asks "Allow access?" the first time the server starts - this file does the same by hand.)

net session >nul 2>&1
if errorlevel 1 (
    echo Please right-click this file and choose "Run as administrator".
    pause
    exit /b 1
)

netsh advfirewall firewall delete rule name="Quiz Battle TCP" >nul 2>&1
netsh advfirewall firewall delete rule name="Quiz Battle UDP" >nul 2>&1
netsh advfirewall firewall add rule name="Quiz Battle TCP" dir=in action=allow protocol=TCP localport=5000 profile=any
netsh advfirewall firewall add rule name="Quiz Battle UDP" dir=in action=allow protocol=UDP localport=5001 profile=any

echo.
echo Done. Now start start_server.bat.
pause
