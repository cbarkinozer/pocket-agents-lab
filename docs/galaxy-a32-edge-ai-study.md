# Galaxy A32 Edge-AI Preparation Study

Captured on 2026-08-14 from the dedicated test handset. Raw command output is retained under `benchmarks/<stage>/`; the repeatable collector is `tools/Collect-DeviceSnapshot.ps1` and all debloat changes can be reversed with `tools/Restore-Debloat.ps1`.

## Device identity

- Samsung Galaxy A32 4G, `SM-A325F`, product `a32tur`, device `a32`.
- Android 13 (API 33), security patch 2025-03-01.
- MediaTek MT6769V/CT / `mt6769t` (Helio G80 family).
- 64-bit ARM primary ABI `arm64-v8a`; 32-bit ARM compatibility ABIs are also exposed.
- Eight ARMv8 cores. `/proc/cpuinfo` identifies six Cortex-A55-class cores (`0xd05`); the SoC also has two Cortex-A75-class performance cores.
- Mali-G52 MC2 GPU; OpenGL ES 3.2 and a Vulkan driver are exposed.
- Android reports 5,791,276 KiB (5.52 GiB) RAM. The difference from marketed 6 GB is hardware/kernel reservation and unit convention.
- CPU features include NEON/ASIMD, FP16, dot-product (`asimddp`), AES, SHA and CRC instructions. These are relevant to quantized CPU inference.
- `cmd neuralnetworks` is not published as a shell service on this firmware. This does not establish that NNAPI is absent; an app-level delegate test is needed.

## Method

Each snapshot collects `/proc/meminfo`, `dumpsys meminfo`, `/proc/cpuinfo`, cpufreq files, `dumpsys thermalservice`, `dumpsys battery`, thermal sysfs inventory, `ps -A`, a one-shot `top`, and complete system/user/disabled package inventories.

The AI workload is a bundled 4,276,352-byte uint8 MobileNet V1 1.0 224 TensorFlow Lite model. Every comparison used TensorFlow Lite 2.14, four CPU threads, identical deterministic input, one first inference, then continuous inference for 60 seconds. The app records load time, first inference, average and final-window latency, throughput, process CPU, peak PSS, and battery temperature. This is a CPU benchmark, not an NNAPI/GPU benchmark.

Snapshots were taken soon after controlled reboots, but Android startup work and USB charging were not fully isolated. Consequently, one-shot idle CPU/process/RAM values are noisy and should not be interpreted as precise causal effects.

## Results

> Important correction: Samsung's provisioning layer silently re-enabled the original Stage A/B `disable-user` changes at reboot. A second experiment used `uninstall --user 0`. Eighteen of nineteen Stage A packages then stayed absent; Samsung Story Service returned. All sixteen Stage B packages were automatically restored at boot. The ten Stage C Google disables initially survived one reboot but were restored on a later boot. Therefore the original Stage A/B/C table below is retained as an audit trail, but it is **not a valid cumulative debloat comparison**. The only verified persistent comparison is stock versus `stage-a-persistent`. Later Stage B/C runs are additional runs of essentially the Stage A stable state.

### Verified persistent comparison

| Metric | Stock/minimal | Stage A persistent | Later control 1 | Later control 2 |
|---|---:|---:|---:|---:|
| Available RAM (MiB) | 3174.4 | 3247.8 | 3277.3 | 2695.5 |
| ZRAM/swap used (MiB) | 1016.3 | 320.0 | 376.5 | 92.2 |
| `ps -A` entries | 783 | 796 | 798 | 818 |
| Average inference (ms) | 52.0 | 56.2 | 57.2 | 59.3 |
| Throughput (inferences/s) | 17.13 | 16.46 | 16.20 | 15.60 |
| Benchmark peak PSS (MiB) | 86.2 | 83.7 | 81.5 | 83.9 |
| Battery temperature before/after | 37.5/37.3 C | 37.4/37.7 C | 37.7/37.7 C | 37.8/38.0 C |

The only persistent debloat state was Stage A: `com.dti.samsung`, the Meta stubs, Microsoft App Manager/OneDrive, Samsung Free, AR/emoji/creative extras, Game Home/Tools and Kids Installer remained absent for user 0. `com.samsung.storyservice` was repaired by firmware. `com.facebook.katana` was a data app rather than a system-image package and was removed; reinstall it from an app store if restoration is desired. The other persistent Stage A packages remain present in the system image and can be restored with `cmd package install-existing --user 0`.

### Initial disable-only runs (invalidated for causal comparison)

| Metric | Stock/minimal | Stage A | Stage B | Stage C |
|---|---:|---:|---:|---:|
| Available RAM (MiB) | 3174.4 | 3012.0 | 3280.3 | 2991.9 |
| Approx. unavailable/used RAM (MiB) | 2480.8 | 2640.8 | 2375.3 | 2663.5 |
| File/cache RAM (MiB) | 2769.1 | 2774.4 | 2924.2 | 2612.7 |
| ZRAM total (MiB) | 4096.0 | 4096.0 | 4096.0 | 4096.0 |
| ZRAM/swap used (MiB) | 1016.3 | 209.3 | 387.0 | 160.3 |
| `ps -A` entries | 783 | 821 | 786 | 816 |
| One-shot aggregate idle CPU | 95.9% | 42.8% | 96.1% | 35.3% |
| Battery temperature at snapshot | 37.7 C | 37.2 C | 37.3 C | 37.4 C |
| Framework thermal status | 2 | 3 | 2 | 2 |
| Model load (ms) | 60.5 | 197.4 | 177.1 | 298.2 |
| First inference (ms) | 71.4 | 58.5 | 58.8 | 63.5 |
| Average inference (ms) | 52.0 | 60.0 | 51.0 | 58.4 |
| Final-window inference (ms) | 48.6 | 57.1 | 48.0 | 57.2 |
| Throughput (inferences/s) | 17.13 | 15.32 | 17.38 | 15.83 |
| Benchmark peak PSS (MiB) | 86.2 | 86.7 | 83.7 | 80.8 |
| Temperature before/after 60 s | 37.5/37.3 C | 37.2/37.4 C | 37.3/37.2 C | 37.4/37.6 C |

`dumpsys meminfo` gave a useful stock cross-check: total RAM 5,791,276 KiB, free RAM 3,487,027 KiB by Android's accounting, used RAM 2,951,282 KiB, lost RAM 82,374 KiB, and 234,040 KiB physical ZRAM backing 1,041,408 KiB swapped pages.

The Stage A and C `top` samples happened while post-boot activity was still high, which explains their low one-shot idle percentage and reinforces why they must not be used alone. Process count did not monotonically fall. Android starts components on demand, and `ps -A` includes kernel threads, so package count and process count are not interchangeable.

## Baseline consumers

The stock `top` snapshot showed `system_server` around 355 MiB RSS, Google Play Services around 261 MiB RSS, Microsoft App Manager around 128 MiB, Samsung Rubin/personalization around 115 MiB, Samsung Mate Agent processes around 85 and 109 MiB, Samsung weather around 118 MiB, Samsung MDX around 68 MiB, and Galaxy Store around 106 MiB. RSS includes shared pages and must not be summed as unique RAM; `dumpsys meminfo` PSS is the better ownership view.

Google Play Services was deliberately retained because it provides shared platform APIs and its removal creates broad stability and push/auth side effects. Samsung Rubin, Device Quality Agent, GOS, Device Care, Knox and security components were also retained: some may look optional, but the value of disabling policy, thermal, security, or maintenance infrastructure is uncertain and could contaminate the benchmark more than it helps.

## Debloat stages

The initial pass used `pm disable-user --user 0`; because Samsung repaired it at boot, the corrected pass used `pm uninstall --user 0`. No APK was deleted from `/system`, `/vendor`, or `/product`, and no root, bootloader, partition, ROM, kernel, governor, or firmware action was used.

### Stage A: obvious consumer software

- Meta/Facebook: `com.facebook.appmanager`, `com.facebook.katana`, `com.facebook.services`, `com.facebook.system`.
- Microsoft: `com.microsoft.appmanager`, `com.microsoft.skydrive`.
- Samsung promotion/feed: `com.dti.samsung`, `com.samsung.android.app.spage`.
- AR/creative extras: DOF Viewer, Dressroom, AR Drawing, AR Emoji, AR Zone, Sticker Center, Visual ARs, Story Service.
- Dedicated-device extras: Game Home, Game Tools, Kids Installer.

### Stage B: optional Samsung ecosystem

- Reminder and Routines.
- Beacon Manager, Easy Setup, Mate Agent.
- MDX, MDX Kit and MDX Quickboard cross-device features.
- Samsung Mobile Service and Samsung Cloud.
- Theme Store and Watch Manager stub.
- AllShare file/media sharing, Private Share and Smart Mirroring.

### Stage C: optional Google consumer apps

- Drive, Maps, Meet/Duo, Gmail, Google Search/Assistant.
- Print-service recommendations, Android Auto and YouTube.
- ARCore and Live Transcribe.

## Protected packages and rationale

The following categories were intentionally kept: Android framework and System UI; One UI launcher; Settings; Wi-Fi, Bluetooth, USB/MTP and ADB; network stack and captive portal; Package Installer and Permission Controller; Google Play Services and Google Services Framework; WebView; Android System Intelligence; Device Health Services; setup/config/module/OTA update managers; Samsung update center and OMC agent; Knox/security framework; media providers/codecs; camera, GPU, NNAPI and vendor HAL packages; Samsung Device Care, GOS, thermal and power services.

Knox is deeply integrated into Samsung framework/security behavior. Disabling it wholesale could create boot, credential, policy, or update failures with no demonstrated inference benefit. GOS and thermal/power services were retained because changing them would alter the thermal control variable that this study is trying to measure. WebView remains `com.google.android.webview` version 111.0.5563.116 and was validated after each stage.

## Sysfs and permission findings

Current CPU frequencies were readable during stock capture: the six efficiency cores reported 1.45 GHz and the two performance cores 1.621 GHz at that instant. Maximum-frequency files were `Permission denied`. Governor output showed `schedutil` for accessible policy entries, while per-core reads were denied. These are expected SELinux/DAC restrictions for the non-root shell and were not bypassed.

`dumpsys thermalservice` is the reliable readable temperature source. It labels and scales values as Celsius: stock AP 37.3 C, battery 37.7 C, PA 37.0 C, skin 38.6 C. Raw thermal-zone `type`/`temp` content is not readable through this shell even though zone links are visible, so no raw scale assumption was made. Android framework thermal statuses observed were 2 and 3; Stage A's skin status was 3 during post-boot activity.

## Interpretation and decision

The measurements do not show an inference improvement from debloating. In the verified persistent Stage A state, throughput fell from 17.13 to 16.46 inferences/s (about 3.9%) and latency rose from 52.0 to 56.2 ms. Later repetitions drifted further downward as the phone remained warm and connected, demonstrating time/order confounding rather than a package effect. Available RAM and process count did not improve consistently. Swap was lower after reboots, but reboot recency is the dominant confounder.

The practical result is that reversible debloating reduces unwanted consumer activity and UI clutter, but has not demonstrated a material MobileNet CPU speedup. Keep the current reversible state if the removed features are unwanted, but do not claim a performance gain from this single-pass experiment.

Do not unlock the bootloader, root, install Magisk/custom ROMs, alter governors, or change thermal policy based on these results. A stronger next experiment is repeated randomized A/B runs at a fixed battery charge, unplugged USB power, fixed ambient temperature, airplane mode with controlled Wi-Fi, a five-minute idle settling period, and at least 5-10 repetitions per state. Add TensorFlow Lite CPU versus NNAPI delegate tests and a representative quantized LLM before considering deeper modification. Root or kernel changes would only become justified if repeated profiling identifies a specific controllable bottleneck such as affinity, DVFS response, or thermal throttling.

## Reproduction and restoration

Capture another snapshot:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\Collect-DeviceSnapshot.ps1 -Label new-run
```

Restore every disabled package from all three stages:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\Restore-Debloat.ps1
adb reboot
```

Individual restoration uses:

```powershell
adb shell pm enable PACKAGE_NAME
```

For a package previously removed only for user 0, restoration would instead be:

```powershell
adb shell cmd package install-existing PACKAGE_NAME
```

`disable-user` retains the installed package and was preferred, but this firmware repaired many changes at boot. `uninstall --user 0` removes a package only from user 0 when a system-image copy exists, can lose per-user state, and requires `install-existing` to restore. Facebook itself was a data app on this handset, so it has no system-image copy and must be reinstalled from an app store if desired.
