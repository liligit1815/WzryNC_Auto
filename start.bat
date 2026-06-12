@echo off
cd /d "%~dp0"

if defined WT_SESSION goto :run

where wt.exe >nul 2>&1
if not errorlevel 1 (
    echo [INFO] Launching in Windows Terminal...
    start "" wt.exe cmd /k "%~f0"
    exit /b
)

:run
python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python not found. Install Python 3.11+
    pause
    exit /b 1
)

adb version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] ADB not found. Add to PATH
    pause
    exit /b 1
)

if not exist "venv\Scripts\activate.bat" (
    echo [INFO] Creating virtual environment...
    python -m venv venv
    if errorlevel 1 (
        echo [ERROR] Failed to create venv
        pause
        exit /b 1
    )
)

call venv\Scripts\activate.bat

python -c "import cv2" >nul 2>&1
if errorlevel 1 (
    echo [INFO] Installing dependencies...
    pip install opencv-python numpy rapidocr_onnxruntime -i https://mirrors.aliyun.com/pypi/simple/
    if errorlevel 1 (
        echo [ERROR] Failed to install dependencies
        pause
        exit /b 1
    )
)

echo.
python -u wzry_auto.py
pause
