Android Auto vehicle data probe. Install on a **phone**, not the car.

| Asset | Use |
|---|---|
| `*.aab` | Upload to Play — internal testing track, the only route into a real car |
| `*-release.apk` | Desktop Head Unit, or direct install |
| `*-debug.apk` | Same, but accepts any host |

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
