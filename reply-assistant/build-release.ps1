param(
    [int]$VersionCode = 1,
    [string]$VersionName = '0.1.0'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = Split-Path $root
$jbr = Join-Path $project 'tools/android-studio/jbr'
$env:JAVA_HOME = $jbr
$env:Path = (Join-Path $jbr 'bin') + ';' + $env:Path
$buildScript = Join-Path $root 'build.ps1'
$sdk = Join-Path $project 'android-sdk'
$apksigner = Join-Path $sdk 'build-tools/35.0.0/apksigner.bat'
$key = Join-Path $project '.secrets/replyassistant-release.jks'
$passwordFile = Join-Path $project '.secrets/replyassistant-release.password'
$build = Join-Path $root 'build'
$input = Join-Path $build 'reply-assistant-debug.apk'
$output = Join-Path $build 'reply-assistant-release.apk'

if (!(Test-Path $key) -or !(Test-Path $passwordFile)) {
    throw '缺少项目外 release keystore 或密码文件：.secrets/replyassistant-release.jks / replyassistant-release.password'
}

& powershell -ExecutionPolicy Bypass -File $buildScript -VersionCode $VersionCode -VersionName $VersionName
$password = (Get-Content -LiteralPath $passwordFile -Raw).Trim()
& $apksigner sign --ks $key --ks-key-alias replyassistant --ks-pass "pass:$password" --key-pass "pass:$password" --out $output $input
& $apksigner verify --verbose $output
Remove-Variable password -Force
Write-Host "Built $output"
