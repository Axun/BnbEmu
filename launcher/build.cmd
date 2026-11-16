@echo off
setlocal
cd /d "%~dp0"

where dotnet >nul 2>nul
if errorlevel 1 (
  echo [!] 未找到 dotnet，请先安装 .NET 9 SDK
  exit /b 1
)

echo [*] 发布 BnbEmu Launcher ...
dotnet publish "BnbLauncher\BnbLauncher.csproj" -c Release -o "dist"
if errorlevel 1 (
  echo [!] 发布失败
  exit /b 1
)

echo.
echo [OK] 产物：%~dp0dist\BnbLauncher.exe
endlocal
