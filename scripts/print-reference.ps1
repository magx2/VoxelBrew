param([Parameter(Mandatory)][string]$TargetDirectory, [ValidateSet('PLA','PETG')][string]$Material = 'PLA')
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $TargetDirectory) { throw 'Choose a new output directory' }
New-Item -ItemType Directory -Path $TargetDirectory | Out-Null
$repoRoot = Split-Path $PSScriptRoot -Parent
$cli = Join-Path $repoRoot 'cli\build\install\cli\bin\cli.bat'
$file = Join-Path $TargetDirectory 'print-reference.jcad'
function Invoke-Cad {
    & $cli @args
    if ($LASTEXITCODE -ne 0) { throw "CAD command failed: $args" }
}
Invoke-Cad new $file
Invoke-Cad rectangle $file --id plate --width 60 --height 40
Invoke-Cad extrude $file --id thickness --sketch plate --distance 8
Invoke-Cad circle $file --id hole --center-x 10 --center-y 10 --diameter 4
Invoke-Cad cut $file --id cut --sketch hole --body-feature thickness
Invoke-Cad place $file --instance reference --body thickness.body --x 20 --y 30
Invoke-Cad print-settings $file --material $Material
Invoke-Cad validate $file
Invoke-Cad export $file --format prusa-project --space BUILD_PLATE --output (Join-Path $TargetDirectory 'reference-project.3mf')
Invoke-Cad slice $file --output (Join-Path $TargetDirectory 'reference.gcode')
Invoke-Cad inspect-job (Join-Path $TargetDirectory 'reference.gcode')
