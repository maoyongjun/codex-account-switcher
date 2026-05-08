$ErrorActionPreference = "Stop"

$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$JPackage = "C:\workspace\tools\jdk17\jdk-17.0.18+8\bin\jpackage.exe"
$AppName = "CodexAccountSwitcher"
$Version = "1.0.0"
$JarName = "codex-account-switcher-$Version.jar"
$Target = Join-Path $ProjectRoot "target"
$AppLibs = Join-Path $Target "app-libs"
$Dist = Join-Path $Target "dist"
$AppImage = Join-Path $Dist $AppName

if (-not (Test-Path -LiteralPath $JPackage)) {
    throw "Missing JDK17 jpackage.exe: $JPackage"
}

Push-Location $ProjectRoot
try {
    mvn clean package

    if (-not (Test-Path -LiteralPath $AppLibs)) {
        New-Item -ItemType Directory -Force -Path $AppLibs | Out-Null
    }
    Copy-Item -LiteralPath (Join-Path $Target $JarName) -Destination (Join-Path $AppLibs $JarName) -Force

    if (Test-Path -LiteralPath $AppImage) {
        $resolvedProject = (Resolve-Path $ProjectRoot).Path
        $resolvedAppImage = (Resolve-Path $AppImage).Path
        if (-not $resolvedAppImage.StartsWith($resolvedProject, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove app image outside project root: $resolvedAppImage"
        }
        Remove-Item -LiteralPath $AppImage -Recurse -Force
    }

    & $JPackage `
        --type app-image `
        --dest $Dist `
        --name $AppName `
        --input $AppLibs `
        --main-jar $JarName `
        --main-class com.juchat.codexswitcher.Launcher `
        --app-version $Version `
        --vendor "ju-chat" `
        --java-options "-Dfile.encoding=UTF-8"

    $Exe = Join-Path $AppImage "$AppName.exe"
    if (-not (Test-Path -LiteralPath $Exe)) {
        throw "jpackage finished but exe was not found: $Exe"
    }
    Write-Host "Created: $Exe"
} finally {
    Pop-Location
}
