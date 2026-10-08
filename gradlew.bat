@echo off
setlocal
set "ROOT=%~dp0"
if not defined JAVA_HOME if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%ROOT%tools\bootstrap-gradle.ps1"
if errorlevel 1 exit /b 1
call "%ROOT%.tools\gradle-8.13\bin\gradle.bat" -p "%ROOT%" %*
exit /b %ERRORLEVEL%
