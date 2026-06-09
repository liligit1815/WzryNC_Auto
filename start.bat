@echo off
chcp 65001 >nul 2>&1
cd /d "%~dp0"

REM 检查Python
python --version >nul 2>&1
if errorlevel 1 (
    echo ❌ 未检测到Python，请先安装Python 3.11+
    echo    下载地址: https://www.python.org/downloads/
    pause
    exit /b 1
)

REM 检查ADB
adb version >nul 2>&1
if errorlevel 1 (
    echo ❌ 未检测到ADB，请先安装platform-tools并添加到PATH
    echo    下载地址: https://developer.android.com/tools/releases/platform-tools
    pause
    exit /b 1
)

REM 创建虚拟环境（如果不存在）
if not exist "venv\Scripts\activate.bat" (
    echo 📦 首次运行，正在创建虚拟环境...
    python -m venv venv
    if errorlevel 1 (
        echo ❌ 创建虚拟环境失败
        pause
        exit /b 1
    )
    echo ✅ 虚拟环境创建成功
)

REM 激活虚拟环境
call venv\Scripts\activate.bat

REM 检查依赖是否已安装
python -c "import cv2" >nul 2>&1
if errorlevel 1 (
    echo 📦 首次运行，正在安装依赖...
    pip install opencv-python numpy rapidocr_onnxruntime -i https://mirrors.aliyun.com/pypi/simple/
    if errorlevel 1 (
        echo ❌ 依赖安装失败，请检查网络连接
        pause
        exit /b 1
    )
    echo ✅ 依赖安装成功
)

REM 启动脚本
echo.
python -u wzry_auto.py
pause
