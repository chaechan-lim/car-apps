# car-apps

Diagnostic probes that measure what vehicle data an ordinary third-party app can
actually read — on Android Auto and on Android Automotive OS.

Google documents which fields *exist*. It does not publish which fields a given
head unit *answers*, and in practice a good number come back `UNAVAILABLE` or
`UNIMPLEMENTED`. There is a long-running Android Auto community thread titled
["Why CarSensors/CarInfo API data availability is a mistery?"](https://support.google.com/androidauto/thread/245937466/why-carsensors-carinfo-api-data-availability-is-a-mistery).
This repo answers that question empirically for a specific car, before any
product decision is made on top of it.

## Modules

| Module | Target | What it does |
|---|---|---|
| `core` | library | `CarAppLibraryProbe` — subscribes to every `CarInfo`/`CarSensors` field and records each one's `CarValue.getStatus()` |
| `projected` | Android Auto | Templated car app (IOT category) showing the probe results grouped by API, plus a logcat report |
| `automotive` | Android Automotive OS | Sideloadable activity that scans **every** `VehiclePropertyIds` constant via `CarPropertyManager` and reports readable / no-access / no-value |

The two apps are independent APKs with different package names, not one build that
adapts. They have to be: the Car App Library ships separate artifacts per platform
(`app-projected` / `app-automotive`), the permissions live in different namespaces
(`com.google.android.gms.permission.CAR_*` vs `android.car.permission.CAR_*`), and
the `automotive` APK gates on `android.hardware.type.automotive` so it will not
install on a phone at all.

The `core` probe is therefore only exercised on Android Auto. On AAOS the
`CarPropertyManager` scan is a superset of what `CarInfo` would surface — `CarInfo`
is a thin wrapper over the same properties — so running both there would measure
the same thing twice.

## What is being measured

### Car App Library — `:projected`, Android Auto

The full surface is small — this is all of it:

| Class | Fields |
|---|---|
| `Model` | `manufacturer`, `name`, `year` |
| `EnergyProfile` | `evConnectorTypes`, `fuelTypes` |
| `EnergyLevel` | `batteryPercent`, `fuelPercent`, `rangeRemainingMeters`, `energyIsLow`, display units |
| `Speed` | `rawSpeedMetersPerSecond`, `displaySpeedMetersPerSecond`, display unit |
| `Mileage` | `odometerMeters`, display unit |
| `TollCard` | `cardState` |
| `CarSensors` | `Accelerometer`, `Gyroscope`, `Compass`, `CarHardwareLocation` |

Documented platform differences, from
[the Car Hardware API docs](https://developer.android.com/training/cars/apps/library/car-hardware-api).
These are Google's claims, not measurements from this repo — the AAOS side is
covered by the property scan instead:

- `Mileage` is **not** available to AAOS apps installed from Google Play, but *is*
  available on Android Auto. Odometer deltas are the accurate basis for fuel/energy
  economy, so this asymmetry matters more than it looks.
- `CarSensors` returns `STATUS_UNIMPLEMENTED` on AAOS — use `SensorManager` /
  `LocationManager` there instead.
- `ExteriorDimensions` is AAOS-only and needs Car App API level 7.

### `CarPropertyManager` — `:automotive`, Android Automotive OS

~250 standard properties exist, but roughly 180 of them sit behind OEM signature
permissions that no third-party app can hold. A "Google built-in" car is only
required to implement four: gear selection, night mode, vehicle speed, and parking
brake. The `automotive` module measures where any particular car sits in that range.

## Prebuilt APKs

Published on the [Releases page](../../releases), built by CI from a clean
checkout. Install on a phone — the AAOS module is not published because it
declares `android.hardware.type.automotive` as required and a phone refuses it.

| Asset | Use |
|---|---|
| `*-release.apk` | Start here |
| `*-debug.apk` | Fallback if the app never appears in the car launcher |

Both are signed with the throwaway `testkey.jks` in this repo, so either installs
over the other and over your own local builds.

**Why a fallback exists:** `ProbeCarAppService` validates the connecting host. Debug
builds accept any host (`ALLOW_ALL_HOSTS_VALIDATOR`); the release build accepts only
`hosts_allowlist_sample`, the signature list shipped with the Car App Library.

### If the app does not appear in the car launcher

**First check how it was installed.** If the APK was sideloaded, that is the
answer — see the box above — and no manifest change will help.

Installed from a trusted source and still missing? Then, in order:

1. **`androidx.car.app.minCarApiLevel` missing from the manifest.** The host refuses
   to bind with *"Min API level not declared in manifest"*. Fixed in v0.1.1.
2. **Category** — v0.1.2 declares POI and IOT together to cover either.
3. **Host validation** — swap the release APK for the debug one to rule it in or out.
4. **Samsung battery optimisation** — apps put to sleep stay visible on the phone but
   are hidden from the Android Auto launcher. Open the app on the phone and turn off
   *"Put unused apps to sleep"*.

All of these fail identically: the APK installs, nothing errors, the app is absent.
Guessing between them costs a release cycle each, so get the host's own reason
instead — with the phone connected:

```
adb logcat | grep -iE "carapp|gearhead|dev\.carapps"
```

## Running it

### Android Auto (`:projected`)

> **A sideloaded APK will not appear in a real car, no matter what the manifest
> says.** Android Auto only runs templated apps installed from a trusted source.
> Its **Unknown sources** developer setting does *not* cover them — per
> [the testing guide](https://developer.android.com/training/cars/testing), that
> setting "applies to media, messaging notifications, and parked apps but doesn't
> apply to apps built using the Android for Cars App Library". A sideloaded build
> installs cleanly, is silently ignored by the host, and logs nothing useful. This
> cost several release cycles to find; do not repeat it.

Three routes, cheapest first:

**Set the installer package** — free, needs adb. The trusted-source check reads
which package installed the APK, and adb can set that:

```
adb uninstall dev.carapps.probe.projected
adb install -i com.android.vending car-probe-auto-<version>-release.apk
```

Not a documented Google method — a community workaround, reported to make
sideloaded templated apps appear in the launcher (this is the same problem
[OsmAnd hit on Android 11+](https://github.com/osmandapp/OsmAnd/issues/15400)).
It may be closed off on current Android. Costs nothing to find out, so try it
before paying for anything.

**Desktop Head Unit** — free, no car. Plain sideloading works here, so it confirms
the app itself is sound even if it cannot answer what a real vehicle reports.

```
./gradlew :projected:installDebug
$ANDROID_HOME/extras/google/auto/desktop-head-unit
```

**Google Play Internal App Sharing** or an **Internal Test Track** — the reliable
way into a real vehicle. Upload an APK, get a link, install from it. Neither track
goes through form-factor review and the 12-testers/14-days rule applies to
production only, so turnaround is an upload rather than a submission. Needs a Play
Console account: $25 once, non-refundable, plus identity verification. Sharing
links expire after 60 days.

Once it runs: open **Car Probe** from the car launcher, drill into each group, tap
**Log**, then export from the phone app (Copy / Share / GitHub issue) or read it
with `adb logcat -s CarProbe`.

Permission prompts appear on the **phone**, not the car screen. Denying one is a
valid experiment — the field then reports as unavailable instead of crashing.

### Android Automotive OS (`:automotive`)

Needs an AAOS target: a real car with ADB (Polestar, Volvo, GM, Honda and others
allow USB/WiFi ADB), or the AAOS emulator image.

```
./gradlew :automotive:installDebug
adb logcat -s CarProbe
```

Building requires the `android.car` system library, provided by the Android
Automotive OS system image / SDK add-on (`useLibrary("android.car")`).

## Status

All three modules compile cleanly (`./gradlew assembleDebug`, AGP 8.7.3 / Kotlin
2.0.21 / compileSdk 35) and both APKs build.

**Nothing here has been run against a real head unit or an emulator.** The car-side
behaviour — which fields resolve, whether the permission prompts appear as expected,
whether the host honours the content limits — is exactly what still needs measuring.

## Notes on scope

The `projected` module declares the **IOT** category. There is no "diagnostics" or
"dashboard" category in the
[car app quality guidelines](https://developer.android.com/docs/quality-guidelines/car-app-quality),
and publishing under a mismatched category is the usual cause of the Play review
rejection *"Auto App Quality Guidelines: Category not permitted"*. For sideloaded
development this only affects which host surfaces list the app.

Neither module is intended for publication. They are bench instruments: the output
decides whether a real feature is possible, not the other way round.
