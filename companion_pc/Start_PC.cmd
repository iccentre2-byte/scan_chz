@echo off
chcp 65001 >nul
cd /d "%~dp0"
if exist "CHZ_PC_Helper.exe" (
  CHZ_PC_Helper.exe %*
  if errorlevel 1 goto error
  pause
  exit /b 0
)
where py >nul 2>&1
if errorlevel 1 (
  echo Нужен Python 3.11 или новее для Windows. Установите его с python.org.
  pause
  exit /b 1
)
if not exist ".venv\Scripts\python.exe" (
  py -3 -c "import sys; assert sys.version_info >= (3,11), 'Required Python 3.11+'"
  if errorlevel 1 goto error
  py -3 -m venv .venv
  if errorlevel 1 goto error
)
.venv\Scripts\python.exe -c "import cryptography,qrcode" >nul 2>&1
if errorlevel 1 (
  .venv\Scripts\python.exe -m pip install -r requirements.txt
  if errorlevel 1 goto error
)
.venv\Scripts\python.exe server.py %*
if errorlevel 1 goto error
pause
exit /b 0
:error
echo Ошибка запуска. Скопируйте текст ошибки из этого окна.
echo Не удаляйте папку данных CHZSetCollector и файл queue.sqlite3.
pause
exit /b 1
