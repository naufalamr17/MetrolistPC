$ErrorActionPreference = 'Stop'
$taskJdk = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '.tools') -Directory -Filter 'jdk-21*' | Select-Object -First 1
if (-not $taskJdk) { throw 'The local Java 21 toolchain is missing. See the desktop usage guide.' }
$env:JAVA_HOME = $taskJdk.FullName
# A non-directory forces Java's supported TCP pipe fallback on this Windows host.
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir="' + (Join-Path $taskJdk.FullName 'lib\modules') + '"'
& (Join-Path $PSScriptRoot 'gradlew.bat') -p (Join-Path $PSScriptRoot 'desktop') test createDistributable packageExe --no-daemon
exit $LASTEXITCODE
