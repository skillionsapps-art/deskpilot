# DeskPilot Server - Windows installer (per-user, no admin needed).
# Installs Python 3.12 if missing, sets up the server in %LOCALAPPDATA%\DeskPilot Server,
# adds Start menu + desktop shortcuts and an "Apps & features" uninstall entry, then starts it.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$AppName = 'DeskPilot Server'
$Root    = Join-Path $env:LOCALAPPDATA $AppName
$AppDir  = Join-Path $Root 'app'
$Venv    = Join-Path $Root 'venv'
$Kit     = $PSScriptRoot

function Say($msg) { Write-Host "  $msg" -ForegroundColor Cyan }

function Test-Python($exe, [string[]]$extra) {
    try {
        $out = & $exe @extra -c "import sys; print(int(sys.version_info >= (3, 9)))" 2>$null
        return ($LASTEXITCODE -eq 0 -and "$out".Trim() -eq '1')
    } catch { return $false }
}

function Find-Python {
    if (Get-Command py -ErrorAction SilentlyContinue) { if (Test-Python 'py' @('-3')) { return @('py', '-3') } }
    $candidates = @(
        "$env:LOCALAPPDATA\Programs\Python\Python313\python.exe",
        "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe",
        "$env:LOCALAPPDATA\Programs\Python\Python311\python.exe",
        "$env:ProgramFiles\Python312\python.exe",
        "$env:ProgramFiles\Python311\python.exe"
    )
    foreach ($c in $candidates) { if ((Test-Path $c) -and (Test-Python $c @())) { return @($c) } }
    $p = Get-Command python -ErrorAction SilentlyContinue      # skips the Microsoft Store stub (it fails the test)
    if ($p -and (Test-Python $p.Source @())) { return @($p.Source) }
    return $null
}

Write-Host ""
Write-Host "  DeskPilot Server setup" -ForegroundColor White
Write-Host "  ----------------------"

$py = Find-Python
if (-not $py) {
    Say "Python not found - downloading Python 3.12 from python.org (about 25 MB)..."
    $arch = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'arm64' } else { 'amd64' }
    $url = "https://www.python.org/ftp/python/3.12.8/python-3.12.8-$arch.exe"
    $tmp = Join-Path $env:TEMP "python-3.12.8-$arch.exe"
    Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing
    Say "Installing Python (just for your user account)..."
    $p = Start-Process -FilePath $tmp -Wait -PassThru -ArgumentList '/quiet', 'InstallAllUsers=0', 'PrependPath=0',
        'Include_launcher=1', 'Include_test=0', 'Include_doc=0', 'Include_tcltk=0', 'Shortcuts=0'
    if ($p.ExitCode -ne 0) { throw "Python installer failed (code $($p.ExitCode))." }
    $py = Find-Python
    if (-not $py) { throw "Python was installed but could not be found. Please restart your PC and run the installer again." }
}
Say "Using Python: $($py -join ' ')"

# stop a running copy so files can be replaced
Get-CimInstance Win32_Process -Filter "Name like 'python%'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*deskpilot_server*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

Say "Copying files to $Root ..."
New-Item -ItemType Directory -Force -Path $Root | Out-Null
if (Test-Path $AppDir) { Remove-Item -Recurse -Force $AppDir }
Copy-Item -Recurse -Force (Join-Path $Kit 'server') $AppDir
Copy-Item -Force (Join-Path $Kit 'deskpilot.ico') (Join-Path $Root 'deskpilot.ico')
Copy-Item -Force (Join-Path $Kit 'uninstall.ps1') (Join-Path $Root 'uninstall.ps1')

Say "Creating a private Python environment..."
$pyExe = $py[0]; $pyArgs = @(); if ($py.Count -gt 1) { $pyArgs = $py[1..($py.Count - 1)] }
& $pyExe @pyArgs -m venv $Venv
if ($LASTEXITCODE -ne 0) { throw "Could not create the Python environment." }
$vpy = Join-Path $Venv 'Scripts\python.exe'
$vpyw = Join-Path $Venv 'Scripts\pythonw.exe'

Say "Installing DeskPilot Server and its components (1-2 minutes)..."
& $vpy -m pip install --upgrade pip --disable-pip-version-check -q
& $vpy -m pip install $AppDir --disable-pip-version-check -q
if ($LASTEXITCODE -ne 0) { throw "Installing components failed. Check your internet connection and run the installer again." }

Say "Adding shortcuts..."
$ws = New-Object -ComObject WScript.Shell
foreach ($dir in @([Environment]::GetFolderPath('Programs'), [Environment]::GetFolderPath('Desktop'))) {
    $lnk = $ws.CreateShortcut((Join-Path $dir "$AppName.lnk"))
    $lnk.TargetPath = $vpyw
    $lnk.Arguments = '-m deskpilot_server'
    $lnk.WorkingDirectory = $Root
    $lnk.IconLocation = (Join-Path $Root 'deskpilot.ico')
    $lnk.Description = 'Control this PC from the DeskPilot Android app'
    $lnk.Save()
}

# Settings > Apps entry (per user)
$uk = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\DeskPilotServer'
New-Item -Path $uk -Force | Out-Null
$uninstallCmd = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$Root\uninstall.ps1`""
Set-ItemProperty $uk -Name DisplayName -Value $AppName
Set-ItemProperty $uk -Name DisplayVersion -Value '1.0.0'
Set-ItemProperty $uk -Name Publisher -Value 'Skillions'
Set-ItemProperty $uk -Name DisplayIcon -Value (Join-Path $Root 'deskpilot.ico')
Set-ItemProperty $uk -Name InstallLocation -Value $Root
Set-ItemProperty $uk -Name UninstallString -Value $uninstallCmd
Set-ItemProperty $uk -Name NoModify -Value 1 -Type DWord
Set-ItemProperty $uk -Name NoRepair -Value 1 -Type DWord

# start when logging in (same key the app's own toggle uses)
Set-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name $AppName -Value "`"$vpyw`" -m deskpilot_server --minimized"

Say "Starting DeskPilot Server..."
Start-Process -FilePath $vpyw -ArgumentList '-m', 'deskpilot_server' -WorkingDirectory $Root

Write-Host ""
Write-Host "  Done! DeskPilot Server is running (look for its icon near the clock)." -ForegroundColor Green
Write-Host "  Its settings page opens in your browser - choose a password there."
Write-Host "  If Windows Firewall asks about 'pythonw', tick Private networks and click Allow."
Write-Host ""
