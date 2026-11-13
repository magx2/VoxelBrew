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
Invoke-Cad @('document', 'new', $Destination)
Invoke-Cad @('designer', 'sketch', 'rectangle', $Destination, '--id', 'plate', '--width', '60', '--height', '40')
Invoke-Cad @('designer', 'solid', 'extrude', $Destination, '--id', 'thickness', '--sketch', 'plate', '--distance', '5')
Invoke-Cad @('designer', 'sketch', 'circle', $Destination, '--id', 'hole', '--center-x', '10', '--center-y', '10', '--diameter', '4')
Invoke-Cad @('designer', 'solid', 'cut', $Destination, '--id', 'cut', '--sketch', 'hole', '--body-feature', 'thickness')
Invoke-Cad @('designer', 'inspect', $Destination, '--point', '10,10,2.5')
# Each command starts another process and reopens the editable document.
Invoke-Cad @('designer', 'feature', 'set', $Destination, '--feature', 'thickness', '--field', 'distance', '--value', '8')
Invoke-Cad @('designer', 'inspect', $Destination, '--point', '10,10,4')
Invoke-Cad @('designer', 'history', 'cursor', $Destination, '--position', '3')
Invoke-Cad @('designer', 'inspect', $Destination)
Invoke-Cad @('designer', 'history', 'cursor', $Destination, '--position', '4')
Invoke-Cad @('designer', 'history', 'list', $Destination)
Write-Host "Saved editable reference part: $Destination"
