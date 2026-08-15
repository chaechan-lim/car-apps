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

The failure is silent — a car app that the host rejects installs fine and simply
never shows up. In order of likelihood:

1. **`androidx.car.app.minCarApiLevel` missing from the manifest.** The host refuses
   to bind with *"Min API level not declared in manifest"*. This bit v0.1.0; fixed
   in v0.1.1.
2. **Unknown sources not enabled** in Android Auto's developer settings.
3. **Samsung battery optimisation** — apps put to sleep stay visible on the phone but
   are hidden from the Android Auto launcher. Open the app on the phone and turn off
   *"Put unused apps to sleep"*.
4. **Host validation** — swap the release APK for the debug one to rule it in or out.
5. **Category** — v0.1.2 declares POI and IOT together to cover either.

Every one of these fails the same way: the APK installs, nothing errors, and the
app is simply absent. Guessing between them is slow, so get the host's own reason
instead — with the phone connected to the head unit:

```
adb logcat | grep -iE "carapp|gearhead|dev\.carapps"
```

The host logs why it skipped an app. That single line settles in seconds what a
release cycle only guesses at.

## Running it

### Android Auto (`:projected`)

Testable today on any Android Auto head unit — no OEM approval, no partner
agreement, no connected-services subscription.

1. In the Android Auto app on the phone, tap the version number 10 times to unlock
   **Developer settings**, then enable **Unknown sources**.
2. `./gradlew :projected:installDebug`
3. Connect to the head unit, or run the Desktop Head Unit:
   `$ANDROID_HOME/extras/google/auto/desktop-head-unit`
4. Open **Car Probe** from the car launcher, drill into each group.
5. Tap **Log**, then capture the full table:
   ```
   adb logcat -s CarProbe
   ```

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
