# -----------------------------------------------------------------------------
#  Everlight Porcelain Miner - direct build (no Gradle / no install required)
#
#  Compiles with the JDK 20 that ships inside the BotWithUs client, packages the
#  classes + script.ini into EverlightMiner.jar, bundles the xapi.public API, and
#  drops the jar into the client's local-scripts folder.
#
#  The client does NOT hot-reload: fully exit and relaunch the client (or
#  re-launch from the Local scripts list) to pick up a rebuilt jar.
#
#  Usage:   pwsh -ExecutionPolicy Bypass -File build.ps1
# -----------------------------------------------------------------------------
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

# 1. Locate the client's bundled JDK 20 (override with $env:BWU_JDK).
$jdk = $env:BWU_JDK
if ([string]::IsNullOrWhiteSpace($jdk)) { $jdk = "C:\Users\kyle\.80a433d0d205ed95\jre" }
$javac = Join-Path $jdk "bin\javac.exe"
if (-not (Test-Path $javac)) {
    throw "javac not found at $javac. Set `$env:BWU_JDK to a JDK 20 home (the folder containing bin\javac.exe)."
}
Write-Host "Using javac: $javac"

# 2. Paths
$srcDir   = Join-Path $root "src\main\java"
$resDir   = Join-Path $root "src\main\resources"
$libsDir  = Join-Path $root "libs"
$outDir   = Join-Path $root "build\classes"
$jarPath  = Join-Path $root "build\EverlightMiner.jar"
$localDir = Join-Path $env:USERPROFILE "BotWithUs\scripts\local"

# 3. Clean + compile (Java 20 with preview - the client's API is preview-tainted).
if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$sources = Get-ChildItem -Recurse -Path $srcDir -Filter *.java | ForEach-Object { $_.FullName }
Write-Host ("Compiling {0} source file(s)..." -f $sources.Count)
& $javac --enable-preview --release 20 -cp "$libsDir\*" -d $outDir @sources
if ($LASTEXITCODE -ne 0) { throw "Compilation failed." }

# 4. Copy resources (script.ini) alongside the classes.
if (Test-Path $resDir) {
    Copy-Item -Path (Join-Path $resDir "*") -Destination $outDir -Recurse -Force
}

# 4b. Bundle the xapi.public API (net.botwithus.api.*) INTO the jar. The client
#     provides the core rs3 API but NOT xapi.public to local scripts, so classes
#     like Bank/Backpack must ship inside our jar.
$xapi = Join-Path $libsDir "xapi-public.jar"
if (Test-Path $xapi) {
    $zin = [System.IO.Compression.ZipFile]::OpenRead($xapi)
    try {
        foreach ($entry in $zin.Entries) {
            if ($entry.FullName.StartsWith("net/botwithus/api/") -and $entry.FullName.EndsWith(".class")) {
                $dest = Join-Path $outDir ($entry.FullName -replace '/', '\')
                New-Item -ItemType Directory -Force -Path (Split-Path $dest -Parent) | Out-Null
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dest, $true)
            }
        }
    } finally { $zin.Dispose() }
    Write-Host "Bundled xapi.public API classes into the jar"
} else {
    Write-Host "WARNING: $xapi not found - Bank/Backpack will be missing at runtime!"
}

# 5. Package build\classes into a jar (a jar is just a zip). Build entries manually
#    so paths use forward slashes (the classloader won't find backslash entries).
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }
$zip = [System.IO.Compression.ZipFile]::Open($jarPath, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    $base = (Resolve-Path $outDir).Path.TrimEnd('\') + '\'
    Get-ChildItem -Recurse -File -Path $outDir | ForEach-Object {
        $entryName = $_.FullName.Substring($base.Length).Replace('\', '/')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $entryName) | Out-Null
    }
} finally {
    $zip.Dispose()
}
Write-Host "Built: $jarPath"

# 6. Deploy to the client's local-scripts folder.
New-Item -ItemType Directory -Force -Path $localDir | Out-Null
Copy-Item -Path $jarPath -Destination $localDir -Force
Write-Host "Deployed to: $localDir"
Write-Host "Done. Fully restart the BotWithUs client to load 'Everlight Porcelain Miner'."
