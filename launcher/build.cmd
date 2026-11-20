@echo off
setlocal EnableExtensions

rem ===============================================================
rem  BnbEmu Launcher build script
rem
rem  IMPORTANT: keep this file ASCII-only and CRLF-terminated.
rem  cmd.exe re-reads batch files by byte offset, so LF line endings
rem  or multi-byte UTF-8 (e.g. Chinese) text make the parser resume in
rem  the middle of a line and execute garbage, such as:
rem    '0"' is not recognized as an internal or external command
rem    The syntax of the command is incorrect.
rem ===============================================================

set "RC=0"

pushd "%~dp0" >nul 2>nul
if errorlevel 1 (
  echo [!] Cannot enter script directory
  set "RC=1"
  goto :done
)

where dotnet >nul 2>nul
if errorlevel 1 (
  echo [!] dotnet not found. Please install the .NET 9 SDK first.
  set "RC=1"
  goto :done
)

if not exist "BnbLauncher\BnbLauncher.csproj" (
  echo [!] Project not found: BnbLauncher\BnbLauncher.csproj
  set "RC=1"
  goto :done
)

echo [*] Publishing BnbEmu Launcher ...
dotnet publish "BnbLauncher\BnbLauncher.csproj" -c Release -o "dist"
if errorlevel 1 (
  echo [!] Publish failed
  set "RC=1"
  goto :done
)

echo.
echo [OK] Output: %~dp0dist\BnbLauncher.exe

:done
popd >nul 2>nul
endlocal & exit /b %RC%
