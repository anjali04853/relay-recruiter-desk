$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Install Java 17 or newer.' }
if (-not (Test-Path -LiteralPath 'target/recruiter-desk-1.0.0.jar')) {
    & mvn package
    if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }
}
$taskTempPath = Join-Path $PSScriptRoot 'target'
& java "-Djava.io.tmpdir=$taskTempPath" "-Djdk.net.unixdomain.tmpdir=$taskTempPath" -jar target/recruiter-desk-1.0.0.jar
