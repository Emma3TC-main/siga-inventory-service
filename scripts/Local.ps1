param([ValidateSet('Init','Start','Stop','Status')][string]$Action = 'Status')

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$infra = [IO.Path]::GetFullPath((Join-Path $root '../siga-infrastructure'))
$local = Join-Path $root '.local'
$configPath = Join-Path $local 'inventory.json'
$compose = @(
    'compose', '--project-name', 'siga-local',
    '--env-file', (Join-Path $infra 'compose/.env.local'),
    '-f', (Join-Path $infra 'compose/compose.local.yml')
)

function Assert-ExitCode {
    if ($LASTEXITCODE -ne 0) {
        throw 'Command failed; existing data and credentials were preserved.'
    }
}

function Assert-ExistingStack {
    docker @compose config --quiet
    Assert-ExitCode

    $postgres = docker inspect siga-local-postgres-1 | ConvertFrom-Json
    Assert-ExitCode
    if ($postgres[0].Config.Labels.'com.docker.compose.project' -ne 'siga-local') {
        throw 'Unexpected PostgreSQL Compose ownership.'
    }
    if ($postgres[0].State.Status -ne 'running') {
        throw 'The existing PostgreSQL container is not running.'
    }
}

function Invoke-AdminSql([string]$query) {
    $query | docker exec -i siga-local-postgres-1 psql -X -v ON_ERROR_STOP=1 -U postgres -d siga
    Assert-ExitCode
}

function Read-InfraEnvironment {
    $values = @{}
    foreach ($line in Get-Content (Join-Path $infra 'compose/.env.local')) {
        if ($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            $values[$Matches[1]] = $Matches[2]
        }
    }
    return $values
}

function Import-InventoryEnvironment {
    if (!(Test-Path $configPath)) {
        throw 'Run scripts/Local.ps1 -Action Init first.'
    }

    $config = Get-Content $configPath -Raw | ConvertFrom-Json
    if ($config.password -notmatch '^[a-f0-9]{64}$') {
        throw 'Invalid stored Inventory configuration.'
    }

    $infraEnvironment = Read-InfraEnvironment
    if (!$infraEnvironment['LOCAL_RABBITMQ_USER'] -or !$infraEnvironment['LOCAL_RABBITMQ_PASSWORD']) {
        throw 'RabbitMQ credentials are missing from the existing local Compose configuration.'
    }

    $env:SPRING_PROFILES_ACTIVE = 'local'
    $env:INVENTORY_PORT = '8083'
    $env:INVENTORY_DB_URL = "jdbc:postgresql://127.0.0.1:$($config.dbPort)/siga"
    $env:INVENTORY_DB_USER = 'siga_inventory'
    $env:INVENTORY_DB_PASSWORD = $config.password
    $env:INVENTORY_RABBIT_HOST = '127.0.0.1'
    $env:INVENTORY_RABBIT_PORT = '5672'
    $env:INVENTORY_RABBIT_USER = $infraEnvironment['LOCAL_RABBITMQ_USER']
    $env:INVENTORY_RABBIT_PASSWORD = $infraEnvironment['LOCAL_RABBITMQ_PASSWORD']
}

Push-Location $root
try {
    switch ($Action) {
        'Init' {
            Assert-ExistingStack
            New-Item -ItemType Directory -Force $local | Out-Null

            if (!(Test-Path $configPath)) {
                $existing = @'
SELECT
    (SELECT count(*) FROM pg_roles WHERE rolname = 'siga_inventory')
  + (SELECT count(*) FROM pg_namespace WHERE nspname = 'inventory');
'@ | docker exec -i siga-local-postgres-1 psql -X -At -v ON_ERROR_STOP=1 -U postgres -d siga
                Assert-ExitCode
                if ([int]$existing -ne 0) {
                    throw 'Inventory exists without its local credentials. Recover them; do not rotate automatically.'
                }

                $port = (docker inspect siga-local-postgres-1 | ConvertFrom-Json)[0].HostConfig.PortBindings.'5432/tcp'[0].HostPort
                Assert-ExitCode
                $bytes = New-Object byte[] 32
                $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
                try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
                $password = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
                @{ dbPort = $port; password = $password } | ConvertTo-Json | Set-Content $configPath
            }

            $config = Get-Content $configPath -Raw | ConvertFrom-Json
            if ($config.password -notmatch '^[a-f0-9]{64}$') {
                throw 'Invalid stored Inventory configuration.'
            }

            $sql = @'
SELECT format(
    'CREATE ROLE siga_inventory LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD %L',
    '__PASSWORD__'
) WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'siga_inventory')
\gexec
GRANT CONNECT ON DATABASE siga TO siga_inventory;
SELECT 'CREATE SCHEMA inventory AUTHORIZATION siga_inventory'
WHERE NOT EXISTS (SELECT FROM pg_namespace WHERE nspname = 'inventory')
\gexec
REVOKE ALL ON SCHEMA inventory FROM PUBLIC;
'@
            Invoke-AdminSql ($sql.Replace('__PASSWORD__', $config.password))
            Write-Host 'Inventory local role and schema are ready; existing credentials were not rotated.'
        }
        'Start' {
            Assert-ExistingStack
            Import-InventoryEnvironment

            $v1Applied = @'
SELECT CASE
    WHEN to_regclass('inventory.flyway_schema_history') IS NULL THEN 0
    ELSE (SELECT count(*) FROM inventory.flyway_schema_history WHERE version = '1' AND success)
END;
'@ | docker exec -i siga-local-postgres-1 psql -X -At -v ON_ERROR_STOP=1 -U postgres -d siga
            Assert-ExitCode
            $temporaryCreateGranted = [int]$v1Applied -eq 0
            if ($temporaryCreateGranted) {
                Invoke-AdminSql 'GRANT CREATE ON DATABASE siga TO siga_inventory;'
            }

            try {

            $pidFile = Join-Path $local 'inventory.pid'
            if (Test-Path $pidFile) {
                $running = Get-Process -Id ([int](Get-Content $pidFile)) -ErrorAction SilentlyContinue
                if ($running) {
                    throw 'Recorded Inventory PID is running.'
                }
            }

            $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 8083)
            try { $listener.Start() } finally { $listener.Stop() }

            $previousPreference = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            ./mvnw.cmd -B -DskipTests package
            $buildExitCode = $LASTEXITCODE
            $ErrorActionPreference = $previousPreference
            if ($buildExitCode -ne 0) {
                throw 'Inventory package failed; no runtime was started.'
            }

            $built = Join-Path $root 'target/siga-inventory-service-0.0.1-SNAPSHOT.jar'
            if (!(Test-Path $built)) {
                throw 'Inventory JAR was not produced.'
            }
            $hash = (Get-FileHash $built -Algorithm SHA256).Hash
            $runtime = Join-Path $local 'runtime'
            New-Item -ItemType Directory -Force $runtime | Out-Null
            $jar = Join-Path $runtime "inventory-$hash.jar"
            if (!(Test-Path $jar)) {
                Copy-Item $built $jar
            }

            $process = Start-Process -FilePath (Get-Command java).Source `
                -ArgumentList @('-jar', ('"{0}"' -f $jar)) `
                -WorkingDirectory $root -WindowStyle Hidden -PassThru `
                -RedirectStandardOutput (Join-Path $local 'inventory.stdout.log') `
                -RedirectStandardError (Join-Path $local 'inventory.stderr.log')
            $process.Id | Set-Content $pidFile
            $jar | Set-Content (Join-Path $local 'inventory.jar')

            $deadline = [DateTime]::UtcNow.AddSeconds(60)
            do {
                $process.Refresh()
                if ($process.HasExited) {
                    throw 'Inventory exited; inspect the private .local logs.'
                }
                try {
                    $health = Invoke-RestMethod 'http://127.0.0.1:8083/actuator/health/readiness' -TimeoutSec 2
                    if ($health.status -eq 'UP') {
                        Write-Host 'Inventory UP on 127.0.0.1:8083'
                        return
                    }
                } catch { }
                Start-Sleep -Milliseconds 500
            } while ([DateTime]::UtcNow -lt $deadline)
            throw 'Inventory readiness timeout; process and private logs were preserved.'
            } finally {
                if ($temporaryCreateGranted) {
                    Invoke-AdminSql 'REVOKE CREATE ON DATABASE siga FROM siga_inventory;'
                }
            }
        }
        'Stop' {
            $pidFile = Join-Path $local 'inventory.pid'
            if (!(Test-Path $pidFile)) { return }
            $inventoryPid = [int](Get-Content $pidFile)
            $process = Get-CimInstance Win32_Process -Filter "ProcessId=$inventoryPid"
            if (!$process) { return }
            $jar = Get-Content (Join-Path $local 'inventory.jar')
            if (!$process.CommandLine.Contains($jar)) {
                throw 'Inventory PID does not match the recorded runtime JAR.'
            }
            Stop-Process -Id $inventoryPid
        }
        'Status' {
            Assert-ExistingStack
            try {
                $health = Invoke-RestMethod 'http://127.0.0.1:8083/actuator/health/readiness' -TimeoutSec 2
                Write-Host "Inventory readiness: $($health.status)"
            } catch {
                Write-Host 'Inventory readiness: DOWN'
            }
        }
    }
} finally {
    Pop-Location
}
