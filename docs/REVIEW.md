# Review: "Veepeak OBDCheck BLE+ Manual and TPD" (Gemini export, 2026-10-31)

This checks the Gemini conversation that seeded OpenOBD against the vendor's own manual, **Veepeak "User Instructions for OBDCheck BLE", V3.2610** (15 pages, PDF dated 2026-10-16), and against the standards the app implements (ELM327 command set, SAE J1979 services, SAE J2012 code format). It then says what OpenOBD does about each point.

**How it was checked.** The Veepeak PDF is the only primary source available. Veepeak's website answered every automated request with HTTP 429 (rate-limited) and manuals.plus refused with HTTP 403, so nothing on those pages was confirmed. Protocol and formula claims were checked by implementing them and testing against a simulated ELM327 (16 unit/end-to-end tests). Where a claim could not be checked, it says so.

Verdicts: **Correct** · **Wrong** (contradicted by the source) · **Unverified** (no primary source reached) · **Incomplete** (true but leaves out something that matters).

## Part 1 — the manual and "TPD"

| Claim | Verdict | Evidence |
|---|---|---|
| The device is the Veepeak OBDCheck BLE+ | Correct (by the user's photos; PDF covers "OBDCheck BLE") | PDF title. |
| PDF manual found | **Incomplete** | Gemini gave links, not a PDF. The PDF you now have (V3.2610) is the real source. The links could not be opened by a script (429/403). |
| Dual-mode Bluetooth (Classic + LE) | Correct | PDF §I.2: "connects via Bluetooth (LE + Classic), not WiFi". "Bluetooth 4.0" is not stated in the PDF: **unverified**. |
| Android: "pair first via system Bluetooth with PIN 1234, then select device inside app" | **Wrong** | PDF §I.2, §IV.4, FAQ 10: on Android, Bluetooth LE is recommended and needs **no** pairing; pairing (PIN 1234) is only for apps that use Classic Bluetooth (e.g. Dr. Prius, BimmerCode). |
| iOS: BLE only, do not pair in system settings | Correct | PDF §I.2, FAQ 10, Troubleshooting 6. |
| 9–16 V, ~35 mA / ~15 mA, −40…+85 °C, 41×50×22 mm, ~45 g | **Unverified** | None of these figures appear in the PDF. Practical point the PDF *does* make: unplug the adapter if the car stands for more than a week (FAQ 19), so the adapter does draw power while parked. |
| Certifications incl. "TELEC/MIC R 214-106504" | **Unverified** | Not in the PDF. If this number came from the label in your photos, check it against the label; otherwise treat it as invented. |
| Protocols: J1850 PWM/VPW, ISO 9141-2, ISO 14230-4, ISO 15765-4 (11/29 bit, 250/500 k) | Plausible, **incomplete** | These are ELM327 protocols 1–9. The PDF lists what is *not* supported, which Gemini omitted: J1939/heavy-duty, MS-CAN (Ford/Mazda), SW-CAN (GM), OBD-I, motorcycles, and possibly WWH-OBD cars (2024+ Ford Maverick). |
| Windows PC support | **Unverified** | Not in the PDF. |
| App list (Car Scanner, Torque, OBD Fusion, BimmerCode/Link non-G, JScan, Carista) | Correct | PDF §III. It also lists apps that do **not** work (BlueDriver, FIXD, Carly, MHD, xHP, ProTool, bootmod3, VCDS, Techstream). |
| (Not mentioned by Gemini) Scope of the hardware | — | PDF §I.3–4, §II, FAQ 12–17: standard OBD-II only (engine/emissions). ABS, SRS, BCM, TPMS, transmission temperature, DPF data, service resets and bi-directional tests are *not* generic OBD-II and only work with specific paid apps on specific cars. One app at a time. |

## Part 2 — the requirements spec

| Spec item | Verdict | What OpenOBD does |
|---|---|---|
| BLE service "FFE0/FFE1 or Nordic UART" | **Unverified** for Veepeak | The UUIDs are discovered at runtime: known layouts (FFF0/FFF1/FFF2, FFE0/FFE1, 18F0/2AF0/2AF1, Nordic UART) first, then any non-standard service with a notify and a write characteristic. The chosen layout is shown under Vehicle › Adapter, so a real Veepeak tells us its UUIDs on first connect. |
| Request MTU 512 | Unnecessary | ELM327 commands are a few bytes. Writes are chunked to 20 bytes (fits the default MTU), which avoids MTU bugs on cheap adapters. |
| Init: ATZ, ATE0, ATL0, ATS0, ATH0, ATSP0, 0100 | Workable, **incomplete** | With headers off (ATH0) answers from several ECUs and multi-frame answers cannot be told apart. OpenOBD uses ATE0, ATL0, **ATS1, ATH1**, ATAT1, ATSP0 and reassembles ISO-TP frames per ECU itself. |
| "25–50 ms between queries to prevent BUFFER FULL" | **Wrong** reasoning | The ELM327 is half-duplex: send, then wait for the `>` prompt. A fixed delay is not what prevents overflow; BUFFER FULL comes from monitor modes (AT MA). OpenOBD keeps exactly one command in flight. |
| RPM = (256A+B)/4; speed = A; coolant = A−40; MAP = A; STFT = (A−128)/1.28 | Correct | All five are unit-tested. |
| Battery voltage via ATRV "under Service $01" | **Wrong** category | ATRV is the adapter measuring pin 16; it is not an OBD service. Service $01 PID $42 is the ECU's own supply voltage. OpenOBD shows both. |
| Mode $04 "verifies OK response from ELM" | **Wrong** | The positive answer to service $04 is `44`. `OK` is only for AT commands. A refusal is `7F 04 22` (conditions not correct, typically engine running). OpenOBD checks for `44` and explains a refusal. |
| Clear guarded by confirmation, KOEO | Correct, **incomplete** | Matches PDF Troubleshooting 15. Gemini omitted that clearing also resets readiness monitors (the car can fail an emissions inspection until re-tested) and that permanent codes ($0A) cannot be cleared by a scan tool. The dialog says both. |
| Services $01, $02, $03, $04, $07, readiness | Correct | All implemented. |
| Missing: $09 VIN / calibration IDs / CVN / ECU names, $0A permanent codes, $06 on-board test results, PID $41 drive-cycle readiness, raw command access | **Gap** | All implemented. The Terminal sends any AT or OBD command (e.g. `ATSH7E0`, `22F190`), which is how an ELM327 exposes manufacturer-specific data to someone who knows the car's PIDs. |
| DTC readout "with likely causes and next steps" | Not done as specified | A reliable cause/fix database is large and needs a licensed source. OpenOBD shows the J2012-style description for about 230 common generic codes (208 listed, plus the per-cylinder misfire and injector patterns), the system the code belongs to, and a one-tap web lookup. |
| Min SDK 21, ">99 % of devices" | Not adopted | The coverage figure is unsourced. OpenOBD uses minSdk 23 (Android 6). |
| Target SDK 35 | Kept | Fine for GitHub/F-Droid. |
| Room + DataStore | Not adopted | Plain SharedPreferences/JSON keep the release APK at **1.6 MB** (spec: < 8 MB). |
| "Absolute dp prohibited for containers" | Overstated | Responsiveness comes from breakpoints: bottom bar and 2-column gauges on phones; navigation rail and 4-across gauges at ≥ 600 dp (landscape, tablets, head units). |
| Auto-disconnect after 5 min to save the car battery | **Wrong** premise | Disconnecting the app does not power the adapter down; it stays on pin 16. Follow the PDF: unplug it if the car stands > 1 week. The app releases the link when you leave it. |
| Odometer by integrating speed | Not adopted | Accumulates error and misses every drive without the phone. OpenOBD reads PID $A6 when the car reports it, else you type it in. |
| Maintenance defaults (oil 10 000 km/12 mo, filters 20 000 km, plugs 60 000 km, brake fluid 24 mo, coolant 50 000 km) | Generic | Used as editable starting points, labelled as such. The owner's manual is the authority. |
| Licence "GPLv3 / Apache-2.0 compatible" | Ambiguous | OpenOBD is GPL-3.0. |

## Target phone

The screenshot shows a Galaxy Note10 (SM-N970F/DS). That model shipped with Android 9 and its last major update was Android 12 (from general knowledge; the screenshot does not show the software version). OpenOBD runs on Android 6+ and was verified on an Android 12 emulator with the Note10's 1080×2280 resolution. The screenshot also shows the phone number, IMEIs and serial number; it is kept out of the repository by `.gitignore`.

## Still to verify with the real adapter

1. Which BLE layout the Veepeak reports (Vehicle › Adapter › GATT service).
2. Classic Bluetooth after pairing with PIN 1234.
3. A car that answers on a pre-CAN protocol (the legacy frame parser is unit-tested only).
