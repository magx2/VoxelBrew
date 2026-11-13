param([Parameter(Mandatory)][string]$TargetDirectory, [ValidateSet('PLA','PETG')][string]$Material = 'PLA')
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $TargetDirectory) { throw 'Choose a new output directory' }
New-Item -ItemType Directory -Path $TargetDirectory | Out-Null
$repoRoot = Split-Path $PSScriptRoot -Parent
$cli = Join-Path $repoRoot 'cli\build\install\cli\bin\cli.bat'
$file = Join-Path $TargetDirectory 'print-reference.vxb'
function Invoke-Cad {
    & $cli @args
    if ($LASTEXITCODE -ne 0) { throw "CAD command failed: $args" }
}
Invoke-Cad document new $file
Invoke-Cad designer sketch rectangle $file --id plate --width 60 --height 40
Invoke-Cad designer solid extrude $file --id thickness --sketch plate --distance 8
Invoke-Cad designer sketch circle $file --id hole --center-x 10 --center-y 10 --diameter 4
Invoke-Cad designer solid cut $file --id cut --sketch hole --body-feature thickness
Invoke-Cad slicer placement add $file --instance reference --body thickness.body --x 20 --y 30
Invoke-Cad slicer process set $file --material $Material
Invoke-Cad slicer validate $file
Invoke-Cad document export $file --format prusa-project --space BUILD_PLATE --output (Join-Path $TargetDirectory 'reference-project.3mf')
Invoke-Cad slicer job slice $file --output (Join-Path $TargetDirectory 'reference.gcode')
Invoke-Cad slicer job inspect (Join-Path $TargetDirectory 'reference.gcode')
