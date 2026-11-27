; Inno Setup 6 script – DeskPilot Server for Windows 10/11 (x64).
; Built by packaging/build.py:  ISCC /DAppVersion=1.0.0 packaging\windows\installer.iss

#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#define AppName "DeskPilot Server"
#define AppExe "DeskPilotServer.exe"

[Setup]
AppId={{6F1E2B7C-9C4D-4E1A-A6B8-3D2C5E7F9A10}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=Skillions
AppPublisherURL=https://skillions.in
AppSupportURL=https://skillions.in
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
OutputDir=..\..\dist\release
OutputBaseFilename=DeskPilotServer-Setup-Windows-x64
SetupIconFile=..\..\build\icons\deskpilot.ico
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
PrivilegesRequired=admin
WizardStyle=modern
Compression=lzma2/ultra64
SolidCompression=yes
CloseApplications=force
RestartApplications=no

[Tasks]
Name: "autostart"; Description: "Start DeskPilot Server when I log in (recommended)"
Name: "desktopicon"; Description: "Create a desktop shortcut"; Flags: unchecked

[Files]
Source: "..\..\dist\DeskPilotServer\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{group}\Uninstall {#AppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Registry]
; Same key the app's own "Start when I log in" toggle uses, so they stay in sync.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "DeskPilot Server"; \
  ValueData: """{app}\{#AppExe}"" --minimized"; Flags: uninsdeletevalue; Tasks: autostart

[Run]
; Let phones reach the server (TCP 9512 + UDP 9511 discovery). Program-scoped rule, removed on uninstall.
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""{#AppName}"""; Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""{#AppName}"" dir=in action=allow program=""{app}\{#AppExe}"" enable=yes profile=any"; \
  Flags: runhidden waituntilterminated; StatusMsg: "Allowing DeskPilot through Windows Firewall..."
Filename: "{app}\{#AppExe}"; Description: "Open DeskPilot Server now"; Flags: nowait postinstall skipifsilent runasoriginaluser

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/f /im {#AppExe}"; Flags: runhidden; RunOnceId: "StopServer"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""{#AppName}"""; Flags: runhidden; RunOnceId: "RemoveFirewallRule"
