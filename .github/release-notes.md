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
- **A floor estimate that needs neither GPS nor the end of the recording.** Two
  things were wrong. Satellites drop anywhere from three minutes before the car
  stops to twelve seconds before, so slicing there measured a ramp one time and a
  parked car the next. And the recording ends when the car's Bluetooth drops, which
  can be after the walk up out of the garage — a climb up the stairs cancels the
  drive down the ramp exactly, which is how the same B5F garage read 2.26 hPa on one
  visit and 0.00 on another. The estimate now runs from the lowest pressure before
  the deepest point up to that peak, and throws away everything after it.
- **Yaw between the entrance and the deepest point**, because a barometer cannot tell
  a ramp from a hill but a spiral can.
- **How long the car's radio stayed up past the deepest point**, which is the number
  that made the old estimate look like a failing sensor.
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
