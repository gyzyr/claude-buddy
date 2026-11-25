# Сборка и установка приложения Claude на телефон + проброс «мозга» по USB.
# Gradle/AGP не работают с кириллицей в пути, поэтому проект временно монтируется как диск B: (subst).
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
if (-not (Test-Path B:\android)) { subst B: "$root" }
# Пути берутся из переменных окружения (ANDROID_HOME, JAVA_HOME, GRADLE_USER_HOME);
# если их нет — стандартные места Android Studio.
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr" }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adbExe = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
if (-not (Test-Path "$root\android\local.properties")) {
    "sdk.dir=" + ($env:ANDROID_HOME -replace '\\', '/') | Set-Content "$root\android\local.properties" -Encoding ascii
}

# Телефон может быть виден несколько раз (кабель, Wi-Fi, Tailscale) — берём одно подключение:
# сначала кабель, потом Tailscale (100.x), потом любое.
$devs = & $adbExe devices | Select-String "\tdevice$" | ForEach-Object { ($_ -split "\s+")[0] }
if (-not $devs) { throw "Телефон не найден: подключите кабель или отладку по Wi-Fi" }
$usb = $devs | Where-Object { $_ -notmatch ":" -and $_ -notmatch "_adb-tls" } | Select-Object -First 1
$ts = $devs | Where-Object { $_ -like "100.*:*" } | Select-Object -First 1
$serial = if ($usb) { $usb } elseif ($ts) { $ts } else { @($devs)[0] }
Write-Host "Телефон: $serial"
function adb { & $adbExe -s $serial @args }
$adb = "adb"

Push-Location B:\android
try {
    .\gradlew.bat assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Сборка не удалась" }
    & $adb install -r "app\build\outputs\apk\debug\app-debug.apk"
    if ($usb) { & $adb reverse tcp:8787 tcp:8787 }   # по кабелю телефон видит мини-Claude как 127.0.0.1:8787
    # Переустановка выключает службу специальных возможностей — включаем обратно, не трогая чужие службы.
    $svc = "app.claudebuddy/app.claudebuddy.service.FeedWatcherService"
    $cur = (& $adb shell settings get secure enabled_accessibility_services).Trim()
    if ($cur -notmatch [regex]::Escape($svc)) {
        $new = if ($cur -eq "null" -or $cur -eq "") { $svc } else { "${cur}:$svc" }
        & $adb shell settings put secure enabled_accessibility_services "$new"
        & $adb shell settings put secure accessibility_enabled 1
    }
} finally { Pop-Location }
Write-Host "Готово. Мозг работает внутри мини-Claude (pc\start-pet.cmd)."
