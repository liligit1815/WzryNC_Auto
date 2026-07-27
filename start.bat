@echo off
cd /d "%~dp0"

if defined WT_SESSION goto :run

where wt.exe >nul 2>&1
if not errorlevel 1 (
    echo [INFO] Launching in Windows Terminal...
    start "" wt.exe cmd.exe /d /k call "%~f0"
    exit /b
)

:run
python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python not found. Install Python 3.11+
    pause
    exit /b 1
)

if defined WZRY_ADB (
    "%WZRY_ADB%" version >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] WZRY_ADB is not executable: %WZRY_ADB%
        pause
        exit /b 1
    )
) else (
    for /f "delims=" %%A in ('where adb 2^>nul') do if not defined WZRY_ADB set "WZRY_ADB=%%A"
    if not defined WZRY_ADB (
        echo [ERROR] ADB not found. Add to PATH or set WZRY_ADB
        pause
        exit /b 1
    )
)

if not defined WZRY_VENV_DIR set "WZRY_VENV_DIR=%CD%\venv"
set "VENV_PYTHON=%WZRY_VENV_DIR%\Scripts\python.exe"

if not exist "%VENV_PYTHON%" (
    echo [INFO] Creating virtual environment...
    python -m venv "%WZRY_VENV_DIR%"
    if errorlevel 1 (
        echo [ERROR] Failed to create venv
        pause
        exit /b 1
    )
)

"%VENV_PYTHON%" scripts\check_requirements.py requirements.txt >nul 2>&1
if errorlevel 1 (
    echo [INFO] Installing dependencies...
    "%VENV_PYTHON%" -m pip install -r requirements.txt -i https://mirrors.aliyun.com/pypi/simple/
    if errorlevel 1 (
        echo [ERROR] Failed to install dependencies
        pause
        exit /b 1
    )
)

powershell -NoProfile -Command "$p=Get-CimInstance Win32_Process | Where-Object {$_.Name -match '^python(w)?\.exe$' -and $_.CommandLine -like '*wzry_auto.py*'}; if ($p) { exit 0 } else { exit 1 }" >nul 2>&1
if not errorlevel 1 (
    echo [WARN] wzry_auto.py is already running. Skipping duplicate launch.
    pause
    exit /b 0
)

if not defined WZRY_LOG_FILE set "WZRY_LOG_FILE=%TEMP%\wzry_run.log"

echo.
"%VENV_PYTHON%" -u scripts\run_with_log.py wzry_auto.py "%WZRY_LOG_FILE%"
set "EXIT_CODE=%ERRORLEVEL%"
echo.
echo [INFO] Log file: %WZRY_LOG_FILE%
pause
exit /b %EXIT_CODE%
