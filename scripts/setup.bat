@echo off
REM Maven For Search 环境配置 - 双击启动包装器
REM 双击此 .bat 文件即可执行 setup.ps1, 无需改注册表
REM 当前目录即为环境安装目录 (JDK/Gradle 缓存会下载到此目录的 jdk21/ 和 gradle-home/ 子目录)

setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1"
pause
