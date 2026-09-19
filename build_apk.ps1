# Automated 1-click build script for Fonarik APK
$ErrorActionPreference = "Stop"

$root = "c:\Antigravity Projects\fonarik"
$tools = "$root\tools"
$app = "$root\app"
$buildDir = "$app\build"

Write-Host "[1/5] Compiling Java classes..." -ForegroundColor Cyan
$javac = "C:\Users\axylax\.gradle\jdks\eclipse_adoptium-21-amd64-windows\jdk-21.0.12.1+1\bin\javac.exe"
$jar = "C:\Users\axylax\.gradle\jdks\eclipse_adoptium-21-amd64-windows\jdk-21.0.12.1+1\bin\jar.exe"
$classesDir = "$buildDir\classes"
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null

$srcFiles = Get-ChildItem -Path "$app\src\main\java" -Filter "*.java" -Recurse | Select-Object -ExpandProperty FullName
& $javac --release 8 -cp "$tools\android-stub.jar" -d $classesDir $srcFiles

Write-Host "[2/5] Converting to Dalvik DEX (classes.dex)..." -ForegroundColor Cyan
java -cp "$tools\dx.jar" com.android.dx.command.Main --dex --output="$buildDir\classes.dex" $classesDir

Write-Host "[3/5] Compiling and linking Android resources..." -ForegroundColor Cyan
$aapt2 = "$tools\aapt2\aapt2.exe"
& $aapt2 compile --dir "$app\src\main\res" -o "$buildDir\compiled_res.zip"
& $aapt2 link -o "$buildDir\app-base.apk" -I "$tools\android-api8.jar" `
    --manifest "$app\src\main\AndroidManifest.xml" "$buildDir\compiled_res.zip" `
    --min-sdk-version 26 --target-sdk-version 35 --version-code 1 --version-name "1.0" `
    --auto-add-overlay

Write-Host "[4/5] Packaging classes.dex into APK..." -ForegroundColor Cyan
& $jar uf "$buildDir\app-base.apk" -C $buildDir classes.dex

Write-Host "[5/5] Signing APK with v1, v2, v3 schemes..." -ForegroundColor Cyan
$apksig = "C:\Users\axylax\.gradle\caches\modules-2\files-2.1\com.android.tools.build\apksig\8.7.0\f005788487574c7d6ba23dea63bf8cb3a4f164a6\apksig-8.7.0.jar"
$scratch = "C:\Users\axylax\.gemini\antigravity\brain\23aa2777-61ec-49c3-9566-456ad110d9ba\scratch"
java -cp "$scratch;$apksig" SignApk

Write-Host "Verifying APK..." -ForegroundColor Cyan
java -cp "$scratch;$apksig" VerifyApk

$finalApk = "$root\fonarik.apk"
Write-Host "`nBUILD SUCCESSFUL! APK location: $finalApk ($((Get-Item $finalApk).Length) bytes)" -ForegroundColor Green
