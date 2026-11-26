@echo off
chcp 65001 >nul
cd /d "%~dp0"
start "安心助手" "%~dp0安心助手.html"
exit /b 0
