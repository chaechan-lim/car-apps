Android Auto vehicle data probe. Install on a **phone**, not the car.

| Asset | Use |
|---|---|
| `parking-probe-*.apk` | **Parking floor probe — an ordinary phone app, just install it** |
| `car-probe-auto-*.aab` | Upload to Play — internal testing track, the only route into a real car |
| `car-probe-auto-*-release.apk` | Desktop Head Unit, or direct install |
| `car-probe-auto-*-debug.apk` | Same, but accepts any host |

`parking-probe` uses no Android Auto at all, so none of the caveats below apply to
it: sideload it, grant the permissions, pick the car's Bluetooth device, and drive.

**This build, for the parking probe:**

- **Share no longer kills the app.** The export used to travel inside the share
  intent, which crosses a Binder transaction capped near a megabyte; a few days of
  drives is well past that, and the process was killed on the way out. It now
  streams to a file and shares that by reference, so size stopped mattering.
- **A floor estimate that does not depend on GPS.** The old one measured from the
  last satellite fix, and the recordings show why that failed: satellites dropped
  anywhere from three minutes before stopping to twelve seconds before, so the same
  garage on the same floor was measured over a ramp one time and over a parked car
  the next. The new estimate takes the last sustained climb before the car stops,
  wherever the satellites happened to give up.
- **Yaw over the same window**, because a barometer cannot tell a ramp from a hill
  but a spiral can.
- **A separation table at the top of the screen**, grouping the labelled drives by
  floor. If two floors' ranges overlap there, the barometer cannot do this — that is
  the whole question, and it is now answered on the phone rather than by exporting.
- Existing recordings are re-scored on open. Nothing needs re-driving.
- Other Bluetooth devices no longer fill the trigger log.

> **Sideloading these APKs into a real car does not work.** Android Auto only runs
> templated apps installed from a trusted source, and its **Unknown sources**
> developer setting does not cover them — it
> ["applies to media, messaging notifications, and parked apps but doesn't apply to
> apps built using the Android for Cars App Library"](https://developer.android.com/training/cars/testing).
> The APK installs cleanly and the car then ignores it, silently.
>
> Use the **Desktop Head Unit** to verify without a car, or **Google Play Internal
> App Sharing** / an **Internal Test Track** to reach a real vehicle. Neither track
> goes through form-factor review.

Launching the app on the phone only shows instructions — the probe itself runs on
the car display, and the phone screen is where its report gets exported.

The two builds differ in host validation: debug accepts any host
(`ALLOW_ALL_HOSTS_VALIDATOR`), release accepts only `hosts_allowlist_sample`. If
the release build installs but never shows up on the car screen, that is the first
thing to suspect, and the debug APK rules it in or out.

Both are signed with the throwaway `testkey.jks` in the repo, so they install over
each other and over local builds.

The AAOS module is not published here: it requires the
`android.hardware.type.automotive` feature and will not install on a phone.
