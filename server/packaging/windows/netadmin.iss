; Inno Setup script for the Windows agent: netadmin-agent + bundled PostgreSQL + WinSW service.
; Built in CI (.github/workflows/server-agent.yml):
;   iscc /DAppVersion=0.1.0.57 server\packaging\windows\netadmin.iss
; Expects build\dist\netadmin-agent (PyInstaller), build\pgsql (PostgreSQL binaries) and
; build\winsw\netadmin-service.exe (WinSW) under server\.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif

[Setup]
AppId={{9C1E5A52-6E0B-4C67-9E4B-2F0E7B9A1D11}
AppName=NetAdmin Server
AppVersion={#AppVersion}
AppVerName=NetAdmin Server {#AppVersion}
AppPublisher=ItManager
AppPublisherURL=https://github.com/iamlex92/ItManager
DefaultDirName={autopf}\NetAdmin
DefaultGroupName=NetAdmin
DisableProgramGroupPage=yes
OutputDir=..\..\build
OutputBaseFilename=NetAdmin-Server-Setup
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
SetupLogging=yes
CloseApplications=no
UninstallDisplayName=NetAdmin Server
UninstallDisplayIcon={app}\netadmin-agent.exe

[Languages]
Name: "ru"; MessagesFile: "compiler:Languages\Russian.isl"

[Messages]
ru.FinishedLabel=Сервер NetAdmin установлен и запущен как служба Windows «NetAdmin». Он работает в безопасном режиме: коммутаторы эмулируются, на оборудование ничего не отправляется.

[Files]
Source: "..\..\build\dist\netadmin-agent\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion
Source: "..\..\build\pgsql\*"; DestDir: "{app}\pgsql"; Flags: recursesubdirs ignoreversion
Source: "..\..\build\winsw\netadmin-service.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "netadmin-service.xml"; DestDir: "{app}"; Flags: ignoreversion

[Dirs]
; Created here so the permissions below apply before anything is written into it.
Name: "{commonappdata}\NetAdmin"

[Icons]
Name: "{group}\Адрес и ключ для приложения"; Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; \
  Parameters: "-NoProfile -WindowStyle Hidden -Command ""Start-Process -Verb RunAs -FilePath '{app}\netadmin-agent.exe' -ArgumentList 'info','--pause'"""; \
  IconFilename: "{app}\netadmin-agent.exe"
Name: "{group}\Настройки сервера"; Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; \
  Parameters: "-NoProfile -WindowStyle Hidden -Command ""Start-Process -Verb RunAs -FilePath notepad.exe -ArgumentList '{commonappdata}\NetAdmin\netadmin.env'"""
Name: "{group}\Удалить NetAdmin Server"; Filename: "{uninstallexe}"

[Run]
; Settings, database, backups and logs: SYSTEM and Administrators only (the file holds the keys).
Filename: "{sys}\icacls.exe"; Parameters: """{commonappdata}\NetAdmin"" /inheritance:r /grant:r *S-1-5-18:(OI)(CI)F *S-1-5-32-544:(OI)(CI)F"; \
  Flags: runhidden waituntilterminated; StatusMsg: "Настройка прав на папку данных..."
Filename: "{app}\netadmin-agent.exe"; \
  Parameters: "init-config ""{commonappdata}\NetAdmin\netadmin.env"" --managed-db ""{commonappdata}\NetAdmin\pgdata"" --pg-bin ""{app}\pgsql\bin"" --backup-dir ""{commonappdata}\NetAdmin\backups"""; \
  Flags: runhidden waituntilterminated; StatusMsg: "Создание настроек..."
Filename: "{app}\netadmin-service.exe"; Parameters: "install"; Flags: runhidden waituntilterminated; StatusMsg: "Регистрация службы..."
; Phones reach the API on TCP 8000, in domain and private networks only.
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""NetAdmin API"""; Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""NetAdmin API"" dir=in action=allow protocol=TCP localport=8000 profile=domain,private"; \
  Flags: runhidden waituntilterminated; StatusMsg: "Правило брандмауэра..."
Filename: "{sys}\net.exe"; Parameters: "start NetAdmin"; Flags: runhidden waituntilterminated; StatusMsg: "Запуск службы (первый запуск создаёт базу, до минуты)..."
Filename: "{app}\netadmin-agent.exe"; Parameters: "info --pause"; Description: "Показать адрес и ключ для приложения"; Flags: postinstall nowait skipifsilent

[UninstallRun]
Filename: "{sys}\net.exe"; Parameters: "stop NetAdmin"; Flags: runhidden waituntilterminated; RunOnceId: "StopService"
Filename: "{app}\netadmin-service.exe"; Parameters: "uninstall"; Flags: runhidden waituntilterminated; RunOnceId: "DeleteService"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""NetAdmin API"""; Flags: runhidden waituntilterminated; RunOnceId: "DeleteFirewallRule"

[UninstallDelete]
; The database, settings and backups in {commonappdata}\NetAdmin stay: they are the inventory.
Type: filesandordirs; Name: "{app}\pgsql"

[Code]
// An upgrade replaces files the running service holds open: stop it first.
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
begin
  Exec(ExpandConstant('{sys}\net.exe'), 'stop NetAdmin', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Result := '';
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then
    SuppressibleMsgBox('База, настройки и резервные копии остались в ' + ExpandConstant('{commonappdata}\NetAdmin') +
      '. Удалите папку вручную, если они больше не нужны.', mbInformation, MB_OK, IDOK);
end;
