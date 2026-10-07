; Telepad's installer for Windows, built with Inno Setup 6 (ISCC.exe, which GitHub's Windows runners have):
;
;   ISCC /DVersion=2.0.0 /DSource=C:\path\to\telepad.exe /DOutputDir=C:\path\to\dist ^
;        /DOutputName=telepad-v2.0.0-windows-x86_64-setup packaging\windows\telepad.iss
;
; It installs for the person who runs it only, so there is no administrator prompt, and it puts Telepad
; in the Start menu and in "Apps" with an uninstaller. Starting at sign-in is offered, and on by default,
; since a phone can only use a Telepad that is running.

#ifndef Version
  #define Version "0.0.0"
#endif
#ifndef Source
  #define Source "..\..\target\release\telepad.exe"
#endif
#ifndef OutputDir
  #define OutputDir "..\..\dist"
#endif
#ifndef OutputName
  #define OutputName "telepad-setup"
#endif

#define AppName "Telepad"
#define AppExe "telepad.exe"
; The key Windows reads at sign-in. The program writes the same entry when "Start at login" is switched on in its menu.
; "--background" is what it is started with then: quietly, with no page opening by itself.
#define RunKey "Software\Microsoft\Windows\CurrentVersion\Run"

[Setup]
; A fixed id: it is how a newer installer knows the older install is the same program, and replaces it.
AppId={{6E1F4C2A-5B7D-4E39-9A0C-3D8B7F21C4A6}
AppName={#AppName}
AppVersion={#Version}
AppVerName={#AppName} {#Version}
VersionInfoVersion={#Version}
VersionInfoDescription={#AppName} setup
AppPublisher=Om Singh
AppPublisherURL=https://github.com/omsingh02/telepad
AppSupportURL=https://github.com/omsingh02/telepad/issues
AppUpdatesURL=https://github.com/omsingh02/telepad/releases
DefaultDirName={autopf}\{#AppName}
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableReadyPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#OutputDir}
OutputBaseFilename={#OutputName}
SetupIconFile=..\icons\telepad.ico
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "autostart"; Description: "Start Telepad when I sign in to Windows"; GroupDescription: "Options:"

[Files]
Source: "{#Source}"; DestDir: "{app}"; DestName: "{#AppExe}"; Flags: ignoreversion
Source: "..\..\LICENSE"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\..\THIRD_PARTY_LICENSES.md"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"

[Registry]
Root: HKCU; Subkey: "{#RunKey}"; ValueType: string; ValueName: "{#AppName}"; ValueData: """{app}\{#AppExe}"" --background"; Tasks: autostart

[Run]
; The first start shows the page with the QR code. Not run by a silent install, which has nobody to show it to.
Filename: "{app}\{#AppExe}"; Description: "Open Telepad and show the QR code"; Flags: nowait postinstall skipifsilent

[Code]
{ Telepad has no window to ask to close, so it is asked to quit the way its own menu does. }
procedure StopRunningTelepad();
var
  ResultCode: Integer;
  Exe: String;
begin
  Exe := ExpandConstant('{app}\{#AppExe}');
  if FileExists(Exe) then
    Exec(Exe, '--quit', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  StopRunningTelepad();
  Result := '';
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
  begin
    StopRunningTelepad();
    { Whether the installer or the program made the entry that starts Telepad at sign-in, it goes. }
    RegDeleteValue(HKEY_CURRENT_USER, '{#RunKey}', '{#AppName}');
  end;
end;
