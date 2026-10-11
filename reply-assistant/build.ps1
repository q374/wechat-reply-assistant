param(
    [int]$VersionCode = 1,
    [string]$VersionName = '0.1.0'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk = Join-Path (Split-Path $root) 'android-sdk'
$java = Join-Path (Split-Path $root) 'tools/android-studio/jbr/bin/java.exe'
$javac = $java -replace 'java.exe','javac.exe'
$jar = $java -replace 'java.exe','jar.exe'
$env:JAVA_HOME = Split-Path (Split-Path $java)
$env:Path = (Split-Path $java) + ';' + $env:Path
$keytool = $java -replace 'java.exe','keytool.exe'
$apksigner = Join-Path $sdk 'build-tools/35.0.0/apksigner.bat'
$build = Join-Path $root 'build'
Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force (Join-Path $build 'classes'), (Join-Path $build 'dex') | Out-Null
$androidJar = Join-Path $env:TEMP 'reply-assistant-android.jar'
Copy-Item (Join-Path $sdk 'platforms/android-35/android.jar') $androidJar -Force
& (Join-Path $sdk 'build-tools/35.0.0/aapt2.exe') compile (Join-Path $root 'res/values/styles.xml') -o (Join-Path $build 'res.zip')
& (Join-Path $sdk 'build-tools/35.0.0/aapt2.exe') compile (Join-Path $root 'res/xml/network_security_config.xml') -o (Join-Path $build 'network-security.zip')
& (Join-Path $sdk 'build-tools/35.0.0/aapt2.exe') compile (Join-Path $root 'res/xml/wechat_fill_accessibility_service.xml') -o (Join-Path $build 'wechat-fill-service.zip')
& (Join-Path $sdk 'build-tools/35.0.0/aapt2.exe') link -o (Join-Path $build 'unsigned.apk') -I $androidJar --manifest (Join-Path $root 'AndroidManifest.xml') (Join-Path $build 'res.zip') (Join-Path $build 'network-security.zip') (Join-Path $build 'wechat-fill-service.zip') --java (Join-Path $build 'generated') --min-sdk-version 26 --target-sdk-version 35 --version-code $VersionCode --version-name $VersionName
& $javac -encoding UTF-8 -source 8 -target 8 -classpath $androidJar -d (Join-Path $build 'classes') (Get-ChildItem (Join-Path $root 'src') -Recurse -Filter *.java | ForEach-Object FullName)
& (Join-Path $sdk 'build-tools/35.0.0/d8.bat') --lib $androidJar --output (Join-Path $build 'dex') (Get-ChildItem (Join-Path $build 'classes') -Recurse -Filter *.class | ForEach-Object FullName)
Copy-Item (Join-Path $build 'dex/classes.dex') (Join-Path $build 'classes.dex')
& (Join-Path $sdk 'build-tools/35.0.0/aapt2.exe') link -o (Join-Path $build 'reply-assistant-debug-unsigned.apk') -I $androidJar --manifest (Join-Path $root 'AndroidManifest.xml') (Join-Path $build 'res.zip') (Join-Path $build 'network-security.zip') (Join-Path $build 'wechat-fill-service.zip') --min-sdk-version 26 --target-sdk-version 35 --version-code $VersionCode --version-name $VersionName
Push-Location $build
& $jar uf reply-assistant-debug-unsigned.apk classes.dex
Pop-Location
& (Join-Path $sdk 'build-tools/35.0.0/zipalign.exe') -f 4 (Join-Path $build 'reply-assistant-debug-unsigned.apk') (Join-Path $build 'reply-assistant-debug.apk')
$debugKeystore = Join-Path $env:TEMP 'reply-assistant-debug.keystore'
if (!(Test-Path $debugKeystore)) {
    & $keytool -genkeypair -keystore $debugKeystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'
}
$signedOutput = Join-Path $env:TEMP 'reply-assistant-debug-signed.apk'
& $apksigner sign --ks $debugKeystore --ks-pass pass:android --key-pass pass:android --out $signedOutput (Join-Path $build 'reply-assistant-debug.apk')
Remove-Item (Join-Path $build 'reply-assistant-debug.apk') -Force
Move-Item $signedOutput (Join-Path $build 'reply-assistant-debug.apk') -Force
Write-Host "Built $build/reply-assistant-debug.apk"
