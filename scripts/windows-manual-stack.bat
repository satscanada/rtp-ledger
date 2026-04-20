@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "ROOT_DIR=%%~fI"

if not defined RTP_TOOLS_DIR set "RTP_TOOLS_DIR=%ROOT_DIR%\tools"

if not defined NATS_SERVER_EXE set "NATS_SERVER_EXE=%RTP_TOOLS_DIR%\nats\nats-server.exe"
if not defined COCKROACH_EXE set "COCKROACH_EXE=%RTP_TOOLS_DIR%\cockroach\cockroach.exe"
if not defined PROMETHEUS_EXE set "PROMETHEUS_EXE=%RTP_TOOLS_DIR%\prometheus\prometheus.exe"
if not defined VICTORIA_METRICS_EXE set "VICTORIA_METRICS_EXE=%RTP_TOOLS_DIR%\victoriametrics\victoria-metrics.exe"
if not defined GRAFANA_HOME set "GRAFANA_HOME=%RTP_TOOLS_DIR%\grafana"
if not defined GRAFANA_EXE set "GRAFANA_EXE=%GRAFANA_HOME%\bin\grafana-server.exe"
if not defined NATS_SURVEYOR_EXE set "NATS_SURVEYOR_EXE=%RTP_TOOLS_DIR%\nats-surveyor\nats-surveyor.exe"

set "LOG_DIR=%ROOT_DIR%\var\windows\logs"
set "PID_DIR=%ROOT_DIR%\var\windows\pids"
set "PROM_DATA_DIR=%ROOT_DIR%\var\prometheus"
set "VM_DATA_DIR=%ROOT_DIR%\var\victoriametrics"
set "CRDB_DATA_DIR=%ROOT_DIR%\var\cockroach"
set "CHRONICLE_MAP_DIR=%ROOT_DIR%\var\chronicle\map"
set "CHRONICLE_QUEUE_DIR=%ROOT_DIR%\var\chronicle\queue"

set "PROM_CONFIG=%ROOT_DIR%\infra\prometheus\prometheus.windows.yml"
set "NATS_CONFIG=%ROOT_DIR%\infra\docker\nats.conf"
set "CLIENT_JAR=%ROOT_DIR%\client\target\client-0.1.0-SNAPSHOT.jar"
set "SERVER_JAR=%ROOT_DIR%\server\target\server-0.1.0-SNAPSHOT.jar"
set "SIM_JAR=%ROOT_DIR%\simulator\target\simulator-0.1.0-SNAPSHOT.jar"

if "%~1"=="" goto :usage

if /I "%~1"=="up" goto :up
if /I "%~1"=="status" goto :status
if /I "%~1"=="stop" goto :stop
if /I "%~1"=="teardown" goto :teardown
if /I "%~1"=="help" goto :usage
if /I "%~1"=="-h" goto :usage
if /I "%~1"=="--help" goto :usage

echo windows-manual-stack: unknown command '%~1'
goto :usage_error

:usage
echo Usage: scripts\windows-manual-stack.bat ^<command^>
echo.
echo Commands:
echo   up         Build jars, start local stack, and apply Cockroach schema/seed
echo   status     Show process and endpoint health
echo   stop       Stop all processes started by this script
echo   teardown   Stop stack and remove local runtime data under var\
echo   help       Show this help
echo.
echo Environment overrides:
echo   RTP_TOOLS_DIR, NATS_SERVER_EXE, COCKROACH_EXE, PROMETHEUS_EXE,
echo   VICTORIA_METRICS_EXE, GRAFANA_HOME, GRAFANA_EXE, NATS_SURVEYOR_EXE
echo.
echo Example:
echo   scripts\windows-manual-stack.bat up
echo   scripts\windows-manual-stack.bat status
exit /b 0

:usage_error
exit /b 1

:prepare_dirs
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
if not exist "%PID_DIR%" mkdir "%PID_DIR%"
if not exist "%PROM_DATA_DIR%" mkdir "%PROM_DATA_DIR%"
if not exist "%VM_DATA_DIR%" mkdir "%VM_DATA_DIR%"
if not exist "%CRDB_DATA_DIR%" mkdir "%CRDB_DATA_DIR%"
if not exist "%CHRONICLE_MAP_DIR%" mkdir "%CHRONICLE_MAP_DIR%"
if not exist "%CHRONICLE_QUEUE_DIR%" mkdir "%CHRONICLE_QUEUE_DIR%"
exit /b 0

:require_file
if exist "%~1" exit /b 0
echo windows-manual-stack: missing required file '%~1'
exit /b 1

:require_command
where "%~1" >nul 2>&1
if errorlevel 1 (
  echo windows-manual-stack: required command '%~1' is not available on PATH
  exit /b 1
)
exit /b 0

:is_pid_running
set "CHECK_PID=%~1"
if "%CHECK_PID%"=="" exit /b 1
tasklist /FI "PID eq %CHECK_PID%" | find "%CHECK_PID%" >nul 2>&1
if errorlevel 1 exit /b 1
exit /b 0

:start_proc
set "NAME=%~1"
set "EXE=%~2"
set "ARGS=%~3"
set "PIDFILE=%PID_DIR%\%NAME%.pid"
set "LOGFILE=%LOG_DIR%\%NAME%.log"

if exist "%PIDFILE%" (
  set /p EXISTING_PID=<"%PIDFILE%"
  call :is_pid_running "!EXISTING_PID!"
  if not errorlevel 1 (
    echo windows-manual-stack: %NAME% already running (PID !EXISTING_PID!)
    exit /b 0
  )
  del /q "%PIDFILE%" >nul 2>&1
)

set "RTP_EXE=%EXE%"
set "RTP_ARGS=%ARGS%"
set "RTP_LOG=%LOGFILE%"
set "RTP_PIDFILE=%PIDFILE%"

powershell -NoProfile -ExecutionPolicy Bypass -Command "$p = Start-Process -FilePath $env:RTP_EXE -ArgumentList $env:RTP_ARGS -RedirectStandardOutput $env:RTP_LOG -RedirectStandardError $env:RTP_LOG -PassThru; Set-Content -Path $env:RTP_PIDFILE -Value $p.Id -Encoding ascii" >nul
if errorlevel 1 (
  echo windows-manual-stack: failed to start %NAME%
  exit /b 1
)

set /p NEW_PID=<"%PIDFILE%"
echo windows-manual-stack: started %NAME% (PID %NEW_PID%)
exit /b 0

:stop_proc
set "NAME=%~1"
set "PIDFILE=%PID_DIR%\%NAME%.pid"

if not exist "%PIDFILE%" (
  echo windows-manual-stack: %NAME% not running (no pid file)
  exit /b 0
)

set /p STOP_PID=<"%PIDFILE%"
call :is_pid_running "%STOP_PID%"
if errorlevel 1 (
  echo windows-manual-stack: %NAME% pid %STOP_PID% not active
  del /q "%PIDFILE%" >nul 2>&1
  exit /b 0
)

taskkill /PID "%STOP_PID%" /T /F >nul 2>&1
if errorlevel 1 (
  echo windows-manual-stack: failed to stop %NAME% (PID %STOP_PID%)
  exit /b 1
)

del /q "%PIDFILE%" >nul 2>&1
echo windows-manual-stack: stopped %NAME% (PID %STOP_PID%)
exit /b 0

:wait_http
set "WAIT_URL=%~1"
set "WAIT_LABEL=%~2"
set "WAIT_MAX=%~3"
if "%WAIT_MAX%"=="" set "WAIT_MAX=60"
set /a WAIT_COUNT=0

:wait_http_loop
set "RTP_WAIT_URL=%WAIT_URL%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "try { $r = Invoke-WebRequest -Uri $env:RTP_WAIT_URL -UseBasicParsing -TimeoutSec 2; if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 500) { exit 0 } else { exit 1 } } catch { exit 1 }" >nul 2>&1
if not errorlevel 1 (
  echo windows-manual-stack: %WAIT_LABEL% is reachable
  exit /b 0
)

set /a WAIT_COUNT+=1
if !WAIT_COUNT! geq %WAIT_MAX% (
  echo windows-manual-stack: timeout waiting for %WAIT_LABEL% ^(%WAIT_URL%^)
  exit /b 1
)
timeout /t 2 /nobreak >nul
goto :wait_http_loop

:cockroach_init
set /a DB_WAIT_COUNT=0
:cockroach_wait_loop
"%COCKROACH_EXE%" sql --insecure --host=127.0.0.1:26257 -e "SELECT 1" >nul 2>&1
if not errorlevel 1 goto :cockroach_apply
set /a DB_WAIT_COUNT+=1
if !DB_WAIT_COUNT! geq 60 (
  echo windows-manual-stack: Cockroach SQL did not become ready in time
  exit /b 1
)
timeout /t 2 /nobreak >nul
goto :cockroach_wait_loop

:cockroach_apply
echo windows-manual-stack: applying Cockroach schema and seed data...
"%COCKROACH_EXE%" sql --insecure --host=127.0.0.1:26257 < "%ROOT_DIR%\infra\db\V1__init.sql"
if errorlevel 1 exit /b 1
"%COCKROACH_EXE%" sql --insecure --host=127.0.0.1:26257 < "%ROOT_DIR%\infra\db\V2__seed.sql"
if errorlevel 1 exit /b 1
echo windows-manual-stack: Cockroach schema and seed applied.
exit /b 0

:up
call :prepare_dirs || exit /b 1
call :require_command mvn || exit /b 1
call :require_command java || exit /b 1
call :require_file "%NATS_SERVER_EXE%" || exit /b 1
call :require_file "%COCKROACH_EXE%" || exit /b 1
call :require_file "%PROMETHEUS_EXE%" || exit /b 1
call :require_file "%VICTORIA_METRICS_EXE%" || exit /b 1
call :require_file "%GRAFANA_EXE%" || exit /b 1
call :require_file "%PROM_CONFIG%" || exit /b 1
call :require_file "%NATS_CONFIG%" || exit /b 1

echo windows-manual-stack: building application jars...
call mvn -DskipTests package
if errorlevel 1 exit /b 1

call :require_file "%CLIENT_JAR%" || exit /b 1
call :require_file "%SERVER_JAR%" || exit /b 1
call :require_file "%SIM_JAR%" || exit /b 1

call :start_proc nats "%NATS_SERVER_EXE%" "-c ""%NATS_CONFIG%""" || exit /b 1
call :wait_http "http://localhost:8222/healthz" "NATS healthz" 30 || exit /b 1

call :start_proc cockroach "%COCKROACH_EXE%" "start-single-node --insecure --listen-addr=127.0.0.1:26257 --http-addr=127.0.0.1:28080 --store=""%CRDB_DATA_DIR%""" || exit /b 1
call :cockroach_init || exit /b 1

if exist "%NATS_SURVEYOR_EXE%" (
  call :start_proc nats-surveyor "%NATS_SURVEYOR_EXE%" "-s nats://sys:rtpSysDev01@127.0.0.1:4222" || exit /b 1
)

call :start_proc prometheus "%PROMETHEUS_EXE%" "--config.file=""%PROM_CONFIG%"" --storage.tsdb.path=""%PROM_DATA_DIR%"" --web.enable-remote-write-receiver" || exit /b 1
call :start_proc victoriametrics "%VICTORIA_METRICS_EXE%" "--storageDataPath=""%VM_DATA_DIR%"" --httpListenAddr=:8428" || exit /b 1

set "GF_SECURITY_ADMIN_USER=admin"
set "GF_SECURITY_ADMIN_PASSWORD=admin"
set "GF_SERVER_HTTP_PORT=3000"
set "GF_PATHS_DATA=%ROOT_DIR%\var\grafana"
set "GF_PATHS_LOGS=%LOG_DIR%"
if not exist "%GF_PATHS_DATA%" mkdir "%GF_PATHS_DATA%"
call :start_proc grafana "%GRAFANA_EXE%" "--homepath ""%GRAFANA_HOME%""" || exit /b 1

call :start_proc rtp-client "java" "-jar ""%CLIENT_JAR%"" --server.port=18080 --rtp.client.nats.servers=nats://127.0.0.1:4222" || exit /b 1
call :start_proc rtp-server "java" "--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED -jar ""%SERVER_JAR%"" --server.port=8081 --rtp.server.nats.servers=nats://127.0.0.1:4222 --spring.datasource.url=jdbc:postgresql://127.0.0.1:26257/defaultdb?sslmode=disable^&reWriteBatchedInserts=true --spring.datasource.username=root --spring.datasource.password= --rtp.server.chronicle.map-path=""%CHRONICLE_MAP_DIR%"" --rtp.server.chronicle.queue-path=""%CHRONICLE_QUEUE_DIR%""" || exit /b 1
call :start_proc rtp-simulator "java" "-jar ""%SIM_JAR%"" --server.port=8082 --rtp.simulator.client-base-url=http://localhost:18080 --rtp.simulator.nats.servers=nats://127.0.0.1:4222" || exit /b 1

echo windows-manual-stack: stack started.
echo windows-manual-stack: use 'scripts\windows-manual-stack.bat status' to verify health.
exit /b 0

:print_status
set "NAME=%~1"
set "PIDFILE=%PID_DIR%\%NAME%.pid"
if not exist "%PIDFILE%" (
  echo   [DOWN] %NAME% ^(no pid file^)
  exit /b 0
)
set /p STATUS_PID=<"%PIDFILE%"
call :is_pid_running "%STATUS_PID%"
if errorlevel 1 (
  echo   [DOWN] %NAME% ^(stale pid %STATUS_PID%^)
) else (
  echo   [UP]   %NAME% ^(PID %STATUS_PID%^)
)
exit /b 0

:status
echo windows-manual-stack: process status
call :print_status nats
call :print_status cockroach
call :print_status nats-surveyor
call :print_status prometheus
call :print_status victoriametrics
call :print_status grafana
call :print_status rtp-client
call :print_status rtp-server
call :print_status rtp-simulator
echo.
echo windows-manual-stack: endpoint checks
call :wait_http "http://localhost:18080/actuator/health" "rtp-client health" 1 >nul 2>&1 && echo   [UP]   rtp-client health || echo   [DOWN] rtp-client health
call :wait_http "http://localhost:8081/actuator/health" "rtp-server health" 1 >nul 2>&1 && echo   [UP]   rtp-server health || echo   [DOWN] rtp-server health
call :wait_http "http://localhost:8082/actuator/health" "rtp-simulator health" 1 >nul 2>&1 && echo   [UP]   rtp-simulator health || echo   [DOWN] rtp-simulator health
call :wait_http "http://localhost:8222/healthz" "nats health" 1 >nul 2>&1 && echo   [UP]   nats health || echo   [DOWN] nats health
call :wait_http "http://localhost:9091/-/healthy" "prometheus health" 1 >nul 2>&1 && echo   [UP]   prometheus health || echo   [DOWN] prometheus health
call :wait_http "http://localhost:3000/api/health" "grafana health" 1 >nul 2>&1 && echo   [UP]   grafana health || echo   [DOWN] grafana health
call :wait_http "http://localhost:28080/health?ready=1" "cockroach health" 1 >nul 2>&1 && echo   [UP]   cockroach health || echo   [DOWN] cockroach health
exit /b 0

:stop
call :stop_proc rtp-simulator
call :stop_proc rtp-server
call :stop_proc rtp-client
call :stop_proc grafana
call :stop_proc victoriametrics
call :stop_proc prometheus
call :stop_proc nats-surveyor
call :stop_proc cockroach
call :stop_proc nats
echo windows-manual-stack: stop complete.
exit /b 0

:teardown
call :stop
echo windows-manual-stack: removing runtime data under '%ROOT_DIR%\var'
if exist "%ROOT_DIR%\var\chronicle" rmdir /s /q "%ROOT_DIR%\var\chronicle"
if exist "%ROOT_DIR%\var\prometheus" rmdir /s /q "%ROOT_DIR%\var\prometheus"
if exist "%ROOT_DIR%\var\victoriametrics" rmdir /s /q "%ROOT_DIR%\var\victoriametrics"
if exist "%ROOT_DIR%\var\cockroach" rmdir /s /q "%ROOT_DIR%\var\cockroach"
if exist "%ROOT_DIR%\var\grafana" rmdir /s /q "%ROOT_DIR%\var\grafana"
if exist "%ROOT_DIR%\var\windows\pids" rmdir /s /q "%ROOT_DIR%\var\windows\pids"
echo windows-manual-stack: teardown complete.
exit /b 0
