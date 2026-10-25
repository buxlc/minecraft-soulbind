param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$MavenHome,
    [string]$Repository = 'F:\codex-cache\maven',
    [string]$TempRoot = 'F:\Temp\SoulBind'
)
$ErrorActionPreference = 'Stop'
if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\javac.exe'))) { throw '请设置 JAVA_HOME，或通过 -JdkHome 指定已有的 Java 25+ JDK。' }
if ($MavenHome) { $maven = Join-Path $MavenHome 'bin\mvn.cmd' }
else { $maven = (Get-Command mvn.cmd -ErrorAction SilentlyContinue).Source }
if (-not $maven -or -not (Test-Path -LiteralPath $maven)) { throw '请将 Maven 加入 PATH，或通过 -MavenHome 指定已有 Maven 目录。' }
foreach ($buildPath in @($JdkHome, $MavenHome, $Repository, $TempRoot)) {
    if ($buildPath -and [IO.Path]::GetFullPath($buildPath) -match '^[Cc]:') { throw '构建工具、缓存与临时文件不能位于 C 盘。' }
}
New-Item -ItemType Directory -Path $Repository,$TempRoot,'F:\codex-cache\soulbind-home' -Force | Out-Null
$savedEnv = @{}
foreach ($name in @('JAVA_HOME','MAVEN_OPTS','TEMP','TMP')) { $savedEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:JAVA_HOME = $JdkHome
    $env:TEMP = $TempRoot; $env:TMP = $TempRoot
    $env:MAVEN_OPTS = "-Duser.home=F:/codex-cache/soulbind-home -Djava.io.tmpdir=$($TempRoot.Replace('\', '/'))"
    Push-Location $PSScriptRoot
    try {
        & $maven -B -ntp "-Dmaven.repo.local=$Repository" clean verify
        if ($LASTEXITCODE -ne 0) { throw "构建失败：$LASTEXITCODE" }
        New-Item -ItemType Directory -Path (Join-Path $PSScriptRoot 'dist') -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'target\SoulBind-1.0.0.jar') -Destination (Join-Path $PSScriptRoot 'dist\SoulBind-1.0.0.jar') -Force
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'src\main\resources\config.yml'),(Join-Path $PSScriptRoot 'src\main\resources\lang.yml'),(Join-Path $PSScriptRoot 'README.md') -Destination (Join-Path $PSScriptRoot 'dist') -Force
    } finally { Pop-Location }
} finally {
    foreach ($name in $savedEnv.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnv[$name], 'Process') }
}
