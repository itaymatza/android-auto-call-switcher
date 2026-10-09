param(
    [string]$Serial,
    [string]$Adb,
    [string]$ApplicationId = $env:APP_APPLICATION_ID,
    [string]$VersionCode = $env:APP_VERSION_CODE,
    [string]$VersionName = $env:APP_VERSION_NAME,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path $PSScriptRoot -Parent
if (-not $ApplicationId) { $ApplicationId = 'org.carcallrouter.companion' }
if (-not $VersionCode) { $VersionCode = '18' }
if (-not $VersionName) { $VersionName = '0.3.0-beta.16' }
$SourceActivity = 'org.carcallrouter.companion.ui.MainActivity'
if (-not $env:ANDROID_HOME -and $env:LOCALAPPDATA) {
    $Candidate = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $Candidate) { $env:ANDROID_HOME = $Candidate }
}
if (-not $Adb) {
    $Found = Get-Command adb -ErrorAction SilentlyContinue
    if ($Found) { $Adb = $Found.Source }
    elseif ($env:ANDROID_HOME -and (Test-Path (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'))) {
        $Adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    } else { throw 'ADB not found. Install Android Studio SDK Platform-Tools, or pass -Adb C:\path\adb.exe.' }
}
if (-not $SkipBuild) {
    if (-not $env:ANDROID_HOME -and -not (Test-Path (Join-Path $Root 'local.properties'))) {
        throw 'Android SDK not configured. Open this project in Android Studio and install SDK Platform 36 and Build-Tools 36.0.0.'
    }
    & (Join-Path $Root 'gradlew.bat') "-PAPP_APPLICATION_ID=$ApplicationId" "-PAPP_VERSION_CODE=$VersionCode" "-PAPP_VERSION_NAME=$VersionName" ':core:check' ':app:ktlintCheck' ':app:assembleDebug' ':app:testDebugUnitTest' ':verification:service-tests:check' ':verification:bluetooth-tests:check' ':verification:call-safety-tests:check' ':app:lintDebug' '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw 'Build or verification failed. Nothing was installed.' }
}
$Apk = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }
$BuildTools = if ($env:ANDROID_BUILD_TOOLS) { $env:ANDROID_BUILD_TOOLS } else { Join-Path $env:ANDROID_HOME 'build-tools\36.0.0' }
$Aapt = Join-Path $BuildTools 'aapt.exe'
$ApkSigner = Join-Path $BuildTools 'apksigner.bat'
if (-not (Test-Path $Aapt)) { throw "aapt not found: $Aapt" }
if (-not (Test-Path $ApkSigner)) { throw "apksigner not found: $ApkSigner" }
$Badging = (& $Aapt dump badging $Apk | Out-String)
if ($LASTEXITCODE -ne 0) { throw 'aapt failed to inspect the APK.' }
$ExpectedPackage = "package: name='$ApplicationId'"
if (-not $Badging.Contains($ExpectedPackage)) { throw "APK package does not match $ApplicationId" }
if (-not $Badging.Contains("versionCode='$VersionCode'")) { throw "APK version code does not match $VersionCode" }
if (-not $Badging.Contains("versionName='$VersionName'")) { throw "APK version name does not match $VersionName" }
if (-not $Badging.Contains("sdkVersion:'34'")) { throw 'APK minimum SDK is not 34.' }
if (-not $Badging.Contains("targetSdkVersion:'36'")) { throw 'APK target SDK is not 36.' }
if (-not $Badging.Contains('application-debuggable')) { throw 'Expected a debuggable test APK.' }
$Permissions = (& $Aapt dump permissions $Apk | Out-String)
foreach ($Permission in @('android.permission.BLUETOOTH_CONNECT', 'android.permission.READ_PHONE_NUMBERS', 'android.permission.MANAGE_ONGOING_CALLS')) {
    if (-not $Permissions.Contains($Permission)) { throw "APK is missing $Permission" }
}
$ManifestTree = (& $Aapt dump xmltree $Apk AndroidManifest.xml | Out-String)
foreach ($ManifestValue in @('org.carcallrouter.companion.telecom.RouterInCallService', 'android.permission.BIND_INCALL_SERVICE', 'android.telecom.InCallService')) {
    if (-not $ManifestTree.Contains($ManifestValue)) { throw "APK manifest is missing $ManifestValue" }
}
& $ApkSigner verify --verbose --print-certs $Apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$ApkSha256 = (Get-FileHash -Algorithm SHA256 $Apk).Hash.ToLowerInvariant()
Write-Host "APK verified: package=$ApplicationId version=$VersionName ($VersionCode) SHA-256=$ApkSha256"
$DeviceText = & $Adb devices
if ($LASTEXITCODE -ne 0) { throw 'adb devices failed' }
$Online = @($DeviceText | ForEach-Object { if ($_ -match '^(\S+)\s+device$') { $Matches[1] } })
if (-not $Serial) {
    if ($Online.Count -ne 1) { throw "Expected exactly one authorized phone, found $($Online.Count). Unlock and authorize the phone or pass -Serial." }
    $Serial = $Online[0]
}
$AdbPrefix = @('-s', $Serial)
function Invoke-Adb {
    param([string[]]$Command)
    & $Adb @AdbPrefix @Command
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed: $($Command -join ' ')" }
}
$UserId = (Invoke-Adb -Command @('shell', 'am', 'get-current-user') | Out-String).Trim()
if ($UserId -notmatch '^\d+$') { throw 'Cannot determine current Android user' }
Invoke-Adb -Command @('install', '--user', $UserId, '-r', $Apk)
Invoke-Adb -Command @('shell', 'cmd', 'appops', 'set', '--user', $UserId, '--uid', $ApplicationId, 'MANAGE_ONGOING_CALLS', 'allow')
Invoke-Adb -Command @('shell', 'cmd', 'appops', 'get', '--user', $UserId, $ApplicationId, 'MANAGE_ONGOING_CALLS')
Invoke-Adb -Command @('shell', 'am', 'start', '--user', $UserId, '-n', "$ApplicationId/$SourceActivity")
Write-Host 'Installed and requested Telecom authorization. In the app: grant permissions, select a target device, test Route now while parked, then enable automation.'
Write-Host 'The selected system dialer remains in control. The app status must show Telecom authorization: true.'
