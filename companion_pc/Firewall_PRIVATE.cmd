@echo off
chcp 65001 >nul
echo Эта команда открывает входящий TCP 18765 только в частной локальной сети.
echo Запускать от администратора только если ТСД не подключается.
choice /c YN /m "Разрешить подключение ТСД (Y/N)?"
if errorlevel 2 exit /b 0
netsh advfirewall firewall add rule name="CHZ Set Collector LAN" dir=in action=allow protocol=TCP localport=18765 profile=private remoteip=localsubnet
pause
