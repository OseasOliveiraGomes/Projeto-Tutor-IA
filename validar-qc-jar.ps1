param([string]$Jar = 'target/sylph-unopar-java-1.0.0.jar', [string]$BuildDirectory = 'target', [string]$Repository = '.m2')
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$jarPath = (Resolve-Path -LiteralPath $Jar).Path
$runId = [DateTimeOffset]::UtcNow.ToOffset([TimeSpan]::FromHours(-3)).ToString('yyyyMMdd_HHmmss_fff')
$outputPath = Join-Path $PSScriptRoot "evidencias/validacoes/replay-$runId"
$testLibPath = Join-Path $outputPath 'test-libs'
New-Item -ItemType Directory -Path $testLibPath | Out-Null
# The replay uses packaged production classes through Spring Boot's launcher.
# Only test classes/resources and their test-only dependencies come from outside the JAR.
$testClasses = Join-Path $BuildDirectory 'test-classes'
if (-not (Test-Path -LiteralPath (Join-Path $testClasses 'br/com/sylph/service/FormattedAttributionIntegrationTest.class'))) {
    throw "Execute primeiro: mvn -Dmaven.repo.local=$Repository -Dsylph.build.directory=$BuildDirectory test"
}
foreach ($dependency in @('org/junit/jupiter/junit-jupiter-api','org/junit/platform/junit-platform-commons',
    'org/opentest4j/opentest4j','org/apiguardian/apiguardian-api','org/mockito/mockito-core',
    'net/bytebuddy/byte-buddy','net/bytebuddy/byte-buddy-agent','org/objenesis/objenesis')) {
    $files = @(Get-ChildItem -LiteralPath (Join-Path $Repository $dependency) -Recurse -Filter '*.jar')
    if ($files.Count -ne 1) { throw "Dependência de teste ausente ou ambígua: $dependency" }
    Copy-Item -LiteralPath $files[0].FullName -Destination $testLibPath
}
$proofPath = Join-Path $outputPath 'PROVA_REPLAY_JAR.json'
$loaderPath = "$testClasses,$testLibPath"
$savedPreference = $ErrorActionPreference
try {
    # Windows PowerShell treats native stderr warnings as ErrorRecords; retain
    # them in the log and use the JVM exit code to decide success/failure.
    $ErrorActionPreference = 'Continue'
    & java '-Dfile.encoding=UTF-8' '-Dloader.main=br.com.sylph.service.FormattedAttributionIntegrationTest' "-Dloader.path=$loaderPath" -cp $jarPath org.springframework.boot.loader.launch.PropertiesLauncher $proofPath *> (Join-Path $outputPath 'replay-console.log')
    $javaExitCode = $LASTEXITCODE
} finally { $ErrorActionPreference = $savedPreference }
if ($javaExitCode -ne 0) { throw "Replay falhou. Consulte $outputPath/replay-console.log" }
$proof = Get-Content -LiteralPath $proofPath -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($location in $proof.production_class_locations.PSObject.Properties) {
    if (-not $location.Value.Contains('sylph-unopar-java-1.0.0.jar')) { throw "Classe fora do JAR: $($location.Name)" }
}
$identity = [ordered]@{ jar=$jarPath; sha256=(Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash; replay_passed=$proof.passed; proof=$proofPath; production_class_locations=$proof.production_class_locations }
[System.IO.File]::WriteAllText((Join-Path $outputPath 'IDENTIFICACAO_JAR.json'),($identity | ConvertTo-Json -Depth 6),[System.Text.UTF8Encoding]::new($false))
$identity | ConvertTo-Json -Depth 6
