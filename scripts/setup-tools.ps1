param([switch]$AcceptLicenses)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path $PSScriptRoot -Parent
$toolDir = Join-Path $projectDir '.tools'
New-Item -ItemType Directory -Force -Path $toolDir | Out-Null
function Download($url, $destination) {
    if (!(Test-Path -LiteralPath $destination)) {
        & curl.exe --fail --location --retry 3 --silent --show-error $url --output $destination
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $url" }
    }
}
$jdkDir = Get-ChildItem -LiteralPath $toolDir -Directory -Filter 'jdk-17*' | Select-Object -First 1
if (!$jdkDir) {
    $asset = (Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse')[0].binary.package
    $archive = Join-Path $toolDir 'jdk17.zip'
    Download $asset.link $archive
    if ((Get-FileHash $archive -Algorithm SHA256).Hash -ne $asset.checksum) { throw 'JDK checksum mismatch' }
    Expand-Archive -LiteralPath $archive -DestinationPath $toolDir -Force
    $jdkDir = Get-ChildItem -LiteralPath $toolDir -Directory -Filter 'jdk-17*' | Select-Object -First 1
}
$env:JAVA_HOME = $jdkDir.FullName
$sdkDir = Join-Path $toolDir 'android-sdk'
$cmdlineDir = Join-Path $sdkDir 'cmdline-tools'
if (!(Test-Path (Join-Path $cmdlineDir 'latest/bin/sdkmanager.bat'))) {
    $archive = Join-Path $toolDir 'commandlinetools.zip'
    Download 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' $archive
    if ((Get-FileHash $archive -Algorithm SHA256).Hash -ne '98b565cb657b012dae6794cefc0f66ae1efb4690c699b78a614b4a6a3505b003') { throw 'SDK tools checksum mismatch' }
    New-Item -ItemType Directory -Force -Path $cmdlineDir | Out-Null
    Expand-Archive -LiteralPath $archive -DestinationPath $cmdlineDir -Force
    Rename-Item -LiteralPath (Join-Path $cmdlineDir 'cmdline-tools') -NewName 'latest'
}
$env:ANDROID_HOME = $sdkDir
if ($AcceptLicenses) {
    'y' | & (Join-Path $cmdlineDir 'latest/bin/sdkmanager.bat') --sdk_root=$sdkDir 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
} else {
    & (Join-Path $cmdlineDir 'latest/bin/sdkmanager.bat') --sdk_root=$sdkDir 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
}
if ($LASTEXITCODE -ne 0) { throw 'SDK installation failed; check license prompts above.' }
if (!(Test-Path (Join-Path $sdkDir 'platforms/android-35/android.jar'))) { throw 'SDK platform missing. Accept the SDK license and rerun.' }
Set-Content -LiteralPath (Join-Path $projectDir 'local.properties') -Value ('sdk.dir=' + $sdkDir.Replace('\','/')) -Encoding ascii
Write-Host "Toolchain ready. JAVA_HOME=$env:JAVA_HOME"
