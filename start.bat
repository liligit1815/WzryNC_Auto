@echo off
cd /d "%~dp0"

python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python not found. Install Python 3.11+ from https://www.python.org/downloads/
    pause
    exit /b 1
)

adb version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] ADB not found. Install platform-tools and add to PATH
    pause
    exit /b 1
)

if not exist "venv\Scripts\activate.bat" (
    echo [INFO] Creating virtual environment...
    python -m venv venv
    if errorlevel 1 (
        echo [ERROR] Failed to create virtual environment
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
