param([string]$Destination = '')
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if (!$Destination) { $Destination = Join-Path $repoRoot 'build/demo/reference.vxb' }
$Destination = [System.IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
$cli = Join-Path $repoRoot 'cli/build/install/cli/bin/cli.bat'
if (!(Test-Path -LiteralPath $cli)) { throw 'Run .\gradlew.bat :cli:installDist first' }
function Invoke-Cad([string[]]$CommandArgs) {
    & $cli @CommandArgs
    if ($LASTEXITCODE -ne 0) { throw "VoxelBrew command failed: $($CommandArgs -join ' ')" }
}
Invoke-Cad @('new', $Destination)
Invoke-Cad @('rectangle', $Destination, '--id', 'plate', '--width', '60', '--height', '40')
Invoke-Cad @('extrude', $Destination, '--id', 'thickness', '--sketch', 'plate', '--distance', '5')
Invoke-Cad @('circle', $Destination, '--id', 'hole', '--center-x', '10', '--center-y', '10', '--diameter', '4')
Invoke-Cad @('cut', $Destination, '--id', 'cut', '--sketch', 'hole', '--body-feature', 'thickness')
Invoke-Cad @('inspect', $Destination, '--point', '10,10,2.5')
# Each command starts another process and reopens the editable document.
Invoke-Cad @('edit', $Destination, '--feature', 'thickness', '--field', 'distance', '--value', '8')
Invoke-Cad @('inspect', $Destination, '--point', '10,10,4')
Invoke-Cad @('cursor', $Destination, '--position', '3')
Invoke-Cad @('inspect', $Destination)
Invoke-Cad @('cursor', $Destination, '--position', '4')
Invoke-Cad @('history', $Destination)
Write-Host "Saved editable reference part: $Destination"
