$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$slicerRoot = Join-Path $repoRoot '.tools\PrusaSlicer-2.7.2'
$executable = Join-Path $slicerRoot 'PrusaSlicer-2.7.2+win64-202402291307\prusa-slicer-console.exe'
function Test-Slicer {
    $probe = & $executable --help
    if ($LASTEXITCODE -ne 0 -or $probe[0] -notlike 'PrusaSlicer-2.7.2+*') { throw 'Slicer version probe failed' }
    $probe | Select-Object -First 1
}
if (Test-Path -LiteralPath $executable) {
    Test-Slicer
    exit 0
}
$downloadRoot = Join-Path $repoRoot '.tools\prusa-download'
New-Item -ItemType Directory -Force -Path $downloadRoot | Out-Null
$archive = Join-Path $downloadRoot 'PrusaSlicer-2.7.2+win64-202402291307.zip'
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest 'https://github.com/prusa3d/PrusaSlicer/releases/download/version_2.7.2/PrusaSlicer-2.7.2%2Bwin64-202402291307.zip' -OutFile $archive
}
$expected = 'A155F1179A987DFC8C5FA34AEED2384D1470F5390848C1FB489448E1E336B5D6'
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expected) { throw 'PrusaSlicer archive checksum mismatch' }
Expand-Archive -LiteralPath $archive -DestinationPath $slicerRoot -Force
Test-Slicer
