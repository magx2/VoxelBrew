param([Parameter(Mandatory = $true)][string]$Installer)
$ErrorActionPreference = 'Stop'
$installerPath = (Resolve-Path -LiteralPath $Installer).Path
$installed = Join-Path $env:LOCALAPPDATA 'Programs\VoxelBrew'
$shortcut = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\VoxelBrew\VoxelBrew.lnk'
$evidence = Join-Path (Get-Location) 'gui\build\package-artifacts\installer'
if (Test-Path -LiteralPath $installed) { throw "Refusing to replace existing $installed" }
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
try {
    $process = Start-Process -FilePath $installerPath -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART' -Wait -PassThru
    if ($process.ExitCode -ne 0) { throw "Installer exited with $($process.ExitCode)" }
    if (!(Test-Path -LiteralPath $shortcut)) { throw "Missing Start menu shortcut: $shortcut" }
    $link = (New-Object -ComObject WScript.Shell).CreateShortcut($shortcut)
    if ($link.TargetPath -ne (Join-Path $installed 'VoxelBrew.exe')) { throw 'Start menu target differs from installed launcher' }
    if ($link.WorkingDirectory -ne $installed) { throw 'Start menu working directory differs from installation' }
    Push-Location $env:TEMP
    try {
        $launched = Start-Process -FilePath $shortcut -PassThru
        Start-Sleep -Seconds 5
        if ($launched.HasExited) { throw 'Start menu launch exited before opening the GUI' }
        Stop-Process -Id $launched.Id -Force
    } finally { Pop-Location }
    $png = Join-Path $evidence 'installed-gui.png'
    Remove-Item -LiteralPath $png -ErrorAction SilentlyContinue
    $smoke = Start-Process -FilePath (Join-Path $installed 'VoxelBrew.exe') -ArgumentList '--smoke-test', '--smoke-output', ('"{0}"' -f $png) -WorkingDirectory $env:TEMP -Wait -PassThru
    if ($smoke.ExitCode -ne 0 -or !(Test-Path -LiteralPath $png)) { throw 'Installed GUI render-and-pick smoke test failed' }
} finally {
    $uninstaller = Join-Path $installed 'unins000.exe'
    if (Test-Path -LiteralPath $uninstaller) {
        $uninstall = Start-Process -FilePath $uninstaller -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART' -Wait -PassThru
        if ($uninstall.ExitCode -ne 0) { throw "Uninstaller exited with $($uninstall.ExitCode)" }
    }
}
if (Test-Path -LiteralPath $installed) { throw "Uninstall left $installed" }
