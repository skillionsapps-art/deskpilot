# Removes DeskPilot Server (settings in %USERPROFILE%\.deskpilot are kept).
$AppName = 'DeskPilot Server'
$Root = Join-Path $env:LOCALAPPDATA $AppName
Get-CimInstance Win32_Process -Filter "Name like 'python%'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*deskpilot_server*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 1
foreach ($dir in @([Environment]::GetFolderPath('Programs'), [Environment]::GetFolderPath('Desktop'))) {
    Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $dir "$AppName.lnk")
}
Remove-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name $AppName -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\DeskPilotServer' -ErrorAction SilentlyContinue
# the running script lives inside $Root, so delete the folder from a detached process
Start-Process -WindowStyle Hidden -FilePath 'cmd.exe' -ArgumentList '/c', "timeout /t 2 >nul & rmdir /s /q `"$Root`""
Write-Host "DeskPilot Server has been removed."
