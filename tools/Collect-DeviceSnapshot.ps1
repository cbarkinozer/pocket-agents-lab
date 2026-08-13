param(
    [Parameter(Mandatory = $true)]
    [string]$Label
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb)) { throw "adb not found at $adb" }

$state = (& $adb get-state 2>&1).Trim()
if ($state -ne "device") { throw "ADB device is not ready: $state" }

$safeLabel = $Label -replace '[^A-Za-z0-9_-]', '_'
$outputDir = Join-Path $PSScriptRoot "..\benchmarks\$safeLabel"
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null

function Save-AdbOutput {
    param([string]$Name, [string[]]$Arguments)
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & $adb @Arguments 2>&1 | Out-File -LiteralPath (Join-Path $outputDir $Name) -Encoding utf8
    $ErrorActionPreference = $oldPreference
}

Save-AdbOutput "device.txt" @("devices", "-l")
Save-AdbOutput "getprop.txt" @("shell", "getprop")
Save-AdbOutput "meminfo-proc.txt" @("shell", "cat", "/proc/meminfo")
Save-AdbOutput "meminfo-dumpsys.txt" @("shell", "dumpsys", "meminfo")
Save-AdbOutput "cpuinfo.txt" @("shell", "cat", "/proc/cpuinfo")
Save-AdbOutput "cpu-files.txt" @("shell", "ls", "-la", "/sys/devices/system/cpu/")
Save-AdbOutput "cpu-current-frequencies.txt" @("shell", "cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq")
Save-AdbOutput "cpu-max-frequencies.txt" @("shell", "cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_max_freq")
Save-AdbOutput "cpu-governors.txt" @("shell", "cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor")
Save-AdbOutput "thermalservice.txt" @("shell", "dumpsys", "thermalservice")
Save-AdbOutput "battery.txt" @("shell", "dumpsys", "battery")
Save-AdbOutput "thermal-files.txt" @("shell", "ls", "-la", "/sys/class/thermal/")
Save-AdbOutput "thermal-zone-types.txt" @("shell", "cat /sys/class/thermal/thermal_zone*/type")
Save-AdbOutput "thermal-zone-values.txt" @("shell", "cat /sys/class/thermal/thermal_zone*/temp")
Save-AdbOutput "processes.txt" @("shell", "ps", "-A")
Save-AdbOutput "top.txt" @("shell", "top", "-b", "-n", "1", "-m", "80")
Save-AdbOutput "packages-all.txt" @("shell", "pm", "list", "packages")
Save-AdbOutput "packages-system.txt" @("shell", "pm", "list", "packages", "-s")
Save-AdbOutput "packages-user.txt" @("shell", "pm", "list", "packages", "-3")
Save-AdbOutput "packages-disabled.txt" @("shell", "pm", "list", "packages", "-d")

$mem = & $adb shell cat /proc/meminfo
function Mem-Kb([string]$key) {
    $line = $mem | Where-Object { $_ -match "^$key`:" } | Select-Object -First 1
    if ($line -match '(\d+)') { return [int64]$Matches[1] }
    return 0
}
$battery = & $adb shell dumpsys battery
$tempTenths = 0
$tempLine = $battery | Where-Object { $_ -match '^\s*temperature:' } | Select-Object -First 1
if ($tempLine -match '(\d+)') { $tempTenths = [int]$Matches[1] }
$processCount = ((& $adb shell ps -A) | Select-Object -Skip 1).Count
$top = & $adb shell top -b -n 1 -m 80
$summary = [ordered]@{
    label = $Label
    capturedUtc = (Get-Date).ToUniversalTime().ToString("o")
    serial = (& $adb get-serialno).Trim()
    model = (& $adb shell getprop ro.product.model).Trim()
    android = (& $adb shell getprop ro.build.version.release).Trim()
    abi = (& $adb shell getprop ro.product.cpu.abi).Trim()
    memTotalKb = Mem-Kb "MemTotal"
    memAvailableKb = Mem-Kb "MemAvailable"
    memFreeKb = Mem-Kb "MemFree"
    buffersKb = Mem-Kb "Buffers"
    cachedKb = (Mem-Kb "Cached") + (Mem-Kb "SReclaimable")
    swapTotalKb = Mem-Kb "SwapTotal"
    swapFreeKb = Mem-Kb "SwapFree"
    swapUsedKb = (Mem-Kb "SwapTotal") - (Mem-Kb "SwapFree")
    processCount = $processCount
    batteryTemperatureC = $tempTenths / 10.0
    batteryLevel = [int](($battery | Where-Object { $_ -match '^\s*level:' } | Select-Object -First 1) -replace '\D','')
    topHeader = ($top | Select-Object -First 5) -join "`n"
}
$summary | ConvertTo-Json | Out-File -LiteralPath (Join-Path $outputDir "summary.json") -Encoding utf8
$summary | Format-List | Out-String | Write-Host
