param([string]$Repository = '.m2')
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
mvn "-Dmaven.repo.local=$Repository" -q -DskipTests package
if ($LASTEXITCODE -ne 0) { throw 'Falha ao compilar a Sylph.' }
java -jar target/sylph-unopar-java-1.0.0.jar
if ($LASTEXITCODE -ne 0) { throw 'Falha ao executar a Sylph.' }
