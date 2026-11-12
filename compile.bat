@echo off
REM Compiles all the .java files into the "out" folder.
REM If "javac" is not in your PATH, this script also searches the usual JDK folders
REM (Android Studio's built-in JDK, Eclipse Adoptium, Oracle, Microsoft, Zulu, Corretto).
cd /d "%~dp0"

where javac >nul 2>nul && goto compile

REM ---- javac is not in the PATH: look in the usual places ----
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "PATH=%JAVA_HOME%\bin;%PATH%"
where javac >nul 2>nul && goto compile

if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\javac.exe" set "PATH=%ProgramFiles%\Android\Android Studio\jbr\bin;%PATH%"
where javac >nul 2>nul && goto compile

if exist "%LocalAppData%\Programs\Android Studio\jbr\bin\javac.exe" set "PATH=%LocalAppData%\Programs\Android Studio\jbr\bin;%PATH%"
where javac >nul 2>nul && goto compile

for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk*") do if exist "%%~D\bin\javac.exe" set "PATH=%%~D\bin;%PATH%"
where javac >nul 2>nul && goto compile

for /d %%D in ("%ProgramFiles%\Java\jdk*") do if exist "%%~D\bin\javac.exe" set "PATH=%%~D\bin;%PATH%"
where javac >nul 2>nul && goto compile

for /d %%D in ("%ProgramFiles%\Microsoft\jdk*") do if exist "%%~D\bin\javac.exe" set "PATH=%%~D\bin;%PATH%"
where javac >nul 2>nul && goto compile

for /d %%D in ("%ProgramFiles%\Zulu\zulu*") do if exist "%%~D\bin\javac.exe" set "PATH=%%~D\bin;%PATH%"
where javac >nul 2>nul && goto compile

for /d %%D in ("%ProgramFiles%\Amazon Corretto\jdk*") do if exist "%%~D\bin\javac.exe" set "PATH=%%~D\bin;%PATH%"
where javac >nul 2>nul && goto compile

echo.
echo Java JDK not found.
echo Install a JDK (for example "Eclipse Temurin 21" from adoptium.net).
echo In the installer, turn ON "Add to PATH" and "Set JAVA_HOME".
echo Then close this window and double-click the .bat file again.
echo.
pause
exit /b 1

:compile
if not exist out mkdir out
javac -d out *.java
if errorlevel 1 (
    echo.
    echo Compile failed - read the errors above.
    pause
    exit /b 1
)
exit /b 0
