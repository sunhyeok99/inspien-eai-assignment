@echo off
rem ------------------------------------------------------------------
rem  실행 도우미 (Windows)
rem    run.bat build | init --name .. --phone .. --email .. | check | server | send [xml] | batch | report | stats [yyyyMMdd]
rem  JDK 17 이상 필요. JAVA_HOME 이 17 미만이면 아래 JDK17 경로를 자기 PC 에 맞게 수정한다.
rem  -Djavax.net.ssl.trustStoreType=Windows-ROOT : 정보 수신 API 인증서 루트(DigiCert G5)가
rem   JDK 17.0.10 미만 cacerts 에 없어서 Windows 인증서 저장소를 사용 (init 에 필요)
rem ------------------------------------------------------------------
setlocal
cd /d "%~dp0"

set "JDK17=C:\Program Files\Java\jdk-17"
set "MVN=C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.2\plugins\maven\lib\maven3\bin\mvn.cmd"

if exist "%JDK17%\bin\java.exe" set "JAVA_HOME=%JDK17%"
set "JAVA=%JAVA_HOME%\bin\java.exe"
if not exist "%JAVA%" set "JAVA=java"

chcp 65001 >nul
if "%~1"=="" goto usage

if /i "%~1"=="build" (
    where mvn >nul 2>nul
    if errorlevel 1 (call "%MVN%" -q -B package) else (call mvn -q -B package)
    goto end
)

if not exist "target\eai-assignment.jar" (
    echo target\eai-assignment.jar 가 없습니다. 먼저 run.bat build 를 실행하세요.
    exit /b 1
)

"%JAVA%" -Djavax.net.ssl.trustStoreType=Windows-ROOT -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar target\eai-assignment.jar %*
goto end

:usage
echo 사용법: run.bat build ^| init --name 이름 --phone 010-0000-0000 --email me@example.com ^| check ^| server ^| send [xml] ^| batch ^| report ^| stats [yyyyMMdd]
exit /b 1

:end
endlocal
