# Sends this PC's brain address (Tailscale IP) and token to the phone app over adb.
# Run once after mini-Claude has started at least once (it creates pc\.brain-token)
# and while the phone is connected by cable or wireless debugging.
$ErrorActionPreference = "Stop"
$tokenFile = Join-Path $PSScriptRoot ".brain-token"
if (-not (Test-Path $tokenFile)) { throw "No pc\.brain-token yet: start mini-Claude (pc\start-pet.cmd) once, then run this again." }
$token = (Get-Content $tokenFile -Raw).Trim()

# Tailscale address of this PC (100.64.0.0/10)
$ip = $null
try { $ip = (& tailscale ip -4 2>$null | Select-Object -First 1) } catch {}
if (-not $ip) {
    $ip = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -match '^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.' } |
        Select-Object -First 1 -ExpandProperty IPAddress
}
if (-not $ip) { throw "Tailscale is not running on this PC. Install it, sign in, and try again." }
$url = "http://$($ip.Trim()):8787"

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adb = Join-Path $sdk "platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }

$tmp = Join-Path $env:TEMP "brain.json"
@{ url = $url; token = $token } | ConvertTo-Json -Compress | Set-Content $tmp -Encoding ascii
try {
    & $adb push $tmp /data/local/tmp/brain.json | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Phone not found: connect it by cable or wireless debugging (adb devices)." }
    & $adb shell "chmod 644 /data/local/tmp/brain.json && run-as app.claudebuddy cp /data/local/tmp/brain.json files/brain.json; rm /data/local/tmp/brain.json"
    if ($LASTEXITCODE -ne 0) { throw "Could not write to the app: install it first with build-and-install.ps1." }
} finally { Remove-Item $tmp -ErrorAction SilentlyContinue }
Write-Host "Done: the phone app will use $url (picked up within a minute or on next app start)."
