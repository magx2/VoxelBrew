$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $repoRoot '.tools'
$downloadRoot = Join-Path $toolRoot 'downloads'
New-Item -ItemType Directory -Force -Path $downloadRoot | Out-Null
$pins = ConvertFrom-StringData (Get-Content -Raw (Join-Path $PSScriptRoot 'native-versions.properties'))
function Get-VerifiedArchive([string]$Url, [string]$Name, [string]$Hash) {
    $archive = Join-Path $downloadRoot $Name
    if (!(Test-Path -LiteralPath $archive)) {
        & curl.exe -fL --retry 3 --output "$archive.part" $Url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $Url" }
        Move-Item -LiteralPath "$archive.part" -Destination $archive -Force
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $Hash) { throw "Checksum mismatch: $archive" }
    return $archive
}
$cmakeArchive = Get-VerifiedArchive "https://github.com/Kitware/CMake/releases/download/v$($pins['cmake.version'])/cmake-$($pins['cmake.version'])-windows-x86_64.zip" "cmake-$($pins['cmake.version'])-windows-x86_64.zip" $pins['cmake.windows.sha256']
if (!(Test-Path -LiteralPath (Join-Path $toolRoot "cmake-$($pins['cmake.version'])-windows-x86_64/bin/cmake.exe"))) {
    Expand-Archive -LiteralPath $cmakeArchive -DestinationPath $toolRoot -Force
}
$occtArchive = Get-VerifiedArchive 'https://codeload.github.com/Open-Cascade-SAS/OCCT/tar.gz/refs/tags/V7_9_0' 'OCCT-7.9.0.tar.gz' $pins['occt.sha256']
if (!(Test-Path -LiteralPath (Join-Path $toolRoot 'OCCT-7_9_0/CMakeLists.txt'))) {
    & tar -xzf $occtArchive -C $toolRoot
    if ($LASTEXITCODE -ne 0) { throw 'OCCT extraction failed' }
}
$sdkVersion = $pins['windows.sdk.package']
$sdkArchive = Get-VerifiedArchive "https://api.nuget.org/v3-flatcontainer/microsoft.windows.sdk.cpp/$sdkVersion/microsoft.windows.sdk.cpp.$sdkVersion.nupkg" "microsoft.windows.sdk.cpp.$sdkVersion.zip" $pins['windows.sdk.sha256']
$sdkLibArchive = Get-VerifiedArchive "https://api.nuget.org/v3-flatcontainer/microsoft.windows.sdk.cpp.x64/$sdkVersion/microsoft.windows.sdk.cpp.x64.$sdkVersion.nupkg" "microsoft.windows.sdk.cpp.x64.$sdkVersion.zip" $pins['windows.sdk.x64.sha256']
$sdkRoot = Join-Path $toolRoot 'windows-sdk'
$sdkLibRoot = Join-Path $toolRoot 'windows-sdk-libs'
if (!(Test-Path -LiteralPath (Join-Path $sdkRoot '.ready'))) {
    New-Item -ItemType Directory -Force -Path $sdkRoot, $sdkLibRoot | Out-Null
    & tar -xf $sdkArchive -C $sdkRoot
    if ($LASTEXITCODE -ne 0) { throw 'Windows SDK extraction failed' }
    & tar -xf $sdkLibArchive -C $sdkLibRoot
    if ($LASTEXITCODE -ne 0) { throw 'Windows SDK libraries extraction failed' }
    $libDestination = Join-Path $sdkRoot "c/Lib/$($pins['windows.sdk.version'])"
    New-Item -ItemType Directory -Force -Path $libDestination | Out-Null
    Copy-Item -LiteralPath (Join-Path $sdkLibRoot 'c/um'), (Join-Path $sdkLibRoot 'c/ucrt') -Destination $libDestination -Recurse -Force
    Set-Content -LiteralPath (Join-Path $sdkRoot '.ready') -Value $sdkVersion
}
Write-Host 'Pinned native sources, CMake and Windows SDK are ready. Run .\gradlew.bat check'
