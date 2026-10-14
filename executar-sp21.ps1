param([string]$Jar = 'target/sylph-unopar-java-1.0.0.jar', [switch]$ValidarSomente)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$jarPath = (Resolve-Path -LiteralPath $Jar).Path
$runId = [DateTimeOffset]::UtcNow.ToOffset([TimeSpan]::FromHours(-3)).ToString('yyyyMMdd_HHmmss_fff')
$resultsRoot = Join-Path $PSScriptRoot 'resultados_sp21'
$runPath = Join-Path $resultsRoot $runId
$buildPath = Join-Path $PSScriptRoot "target/sp21/$runId"
New-Item -ItemType Directory -Path $buildPath | Out-Null
# Read classes and dependencies from the corrected JAR, without rebuilding production.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName.StartsWith('BOOT-INF/classes/') -or $entry.FullName.StartsWith('BOOT-INF/lib/')) {
            if ($entry.FullName.EndsWith('/')) { continue }
            $destination = [System.IO.Path]::GetFullPath((Join-Path $buildPath $entry.FullName))
            if (-not $destination.StartsWith([System.IO.Path]::GetFullPath($buildPath) + [System.IO.Path]::DirectorySeparatorChar)) { throw 'Caminho inválido dentro do JAR' }
            [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($destination)) | Out-Null
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$destination,$false)
        }
    }
} finally { $archive.Dispose() }
$runnerClasses = Join-Path $buildPath 'runner-classes'
New-Item -ItemType Directory -Path $runnerClasses | Out-Null
$classpath = "$buildPath/BOOT-INF/classes;$buildPath/BOOT-INF/lib/*"
& javac -encoding UTF-8 -cp $classpath -d $runnerClasses tools/sp21/AcademicExperiment.java
if ($LASTEXITCODE -ne 0) { throw 'Compilação do executor falhou' }
if ($ValidarSomente) {
    Write-Host 'Executor compilado. Nenhuma pergunta, chamada ao Ollama ou resultado acadêmico foi gerado.'
    return
}
New-Item -ItemType Directory -Path $runPath | Out-Null
Write-Host "Resultados: $runPath"
& java '-Dfile.encoding=UTF-8' -cp "$runnerClasses;$classpath" br.com.sylph.experiment.AcademicExperiment $runPath $jarPath
if ($LASTEXITCODE -ne 0) { throw "Experimento interrompido. Consulte os registros em $runPath" }
