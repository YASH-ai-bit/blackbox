$projectDir = Split-Path $PSScriptRoot -Parent
$jdkDir = Get-ChildItem -LiteralPath (Join-Path $projectDir '.tools') -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdkDir) { $env:JAVA_HOME = $jdkDir.FullName }
$localSdk = Join-Path $projectDir '.tools/android-sdk'
if (Test-Path $localSdk) { $env:ANDROID_HOME = $localSdk }
$env:GRADLE_USER_HOME = Join-Path $projectDir '.tools/gradle-home'
if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME/bin;$env:PATH" }
if ($env:ANDROID_HOME) { $env:PATH = "$env:ANDROID_HOME/platform-tools;$env:PATH" }
