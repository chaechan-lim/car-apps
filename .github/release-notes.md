Android Auto vehicle data probe. Install on a **phone**, not the car.

| Asset | Use |
|---|---|
| `parking-probe-*.apk` | **Parking floor probe — an ordinary phone app, just install it** |
| `car-probe-auto-*.aab` | Upload to Play — internal testing track, the only route into a real car |
| `car-probe-auto-*-release.apk` | Desktop Head Unit, or direct install |
| `car-probe-auto-*-debug.apk` | Same, but accepts any host |

`parking-probe` uses no Android Auto at all, so none of the caveats below apply to
it: sideload it, grant the permissions, pick the car's Bluetooth device, and drive.

**This build, for the parking probe — the first one that names a floor, and the first
that reports how little that proves.**

Fitted on 38 recorded drives, 35 labelled, from one phone, one person, ten days and
**four underground garages**. Scored leave-one-out: **31/35 exactly right (89%), 34/35
within one floor (97%)**.

Read that number next to its rival. Predicting "whatever floor you usually take at this
garage" — a lookup table with no sensor in it — scores **30/35 (86%)**. So the headline
accuracy is mostly measuring a parking habit. The barometer earns its place on the four
drives that broke the habit, where the lookup scores **0/4** and the barometer **3/4**
exactly and 4/4 within one floor. Those are also the only drives where a person is
actually confused about where they parked. Both numbers are now on the screen, so which
one is true stays visible as drives accumulate.

What is *not* established: a first visit to an unfamiliar underground garage, which is
the case this is ultimately for. There are three such drives in the whole set.

- **Satellites decide underground, pressure decides how deep.** Pressure alone cannot
  tell a garage ramp from a road running downhill into a destination — over half the
  surface parks recorded half a level or more of climb. How long satellites stay silent
  does tell them apart: across the labelled drives, surface parks went quiet for at most
  64 s and underground parks for at least 101 s, one exception each way.
- **The climb is found, not windowed.** One garage's descent takes 90 seconds and
  another's 400, so any fixed window clipped one or swallowed the hill before the other.
  The walk back from the deepest point now continues while the climb holds and stops
  where pressure turns back down by more than half a hPa.
- **The deepest point is searched over the last two minutes only.** Ten minutes found the
  *start* of a short drive that had begun in a deeper garage than it ended in, and called
  that the arrival.
- **0.52 hPa per level, measured.** Not the 0.36 that three metres of air would give —
  real garages put more than a storey between levels once ramp runs are counted.
- **Per-garage calibration is gone.** It measured worse twice — 83% against 89% — because
  the garages with enough drives came out at 0.50, 0.51 and 0.54 hPa per level. Buildings
  differ in principle; these do not, and fitting each separately only added the noise of
  its own few drives.
- **The parked notification says a floor**, not a decimal count of levels, and says so
  when that floor is not the usual one here.

Earlier in this build:

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
- **Drives are grouped by where they ended**, within 150 m of the last fix, and a
  garage can be named by hand while labelling — which overrides the guess and spreads
  to every drive that ended there. Absolute pressure differs between two places at the
  same height, by weather, terrain and sensor offset, but none of that reaches the
  estimate, which is a difference measured inside a single drive. Grouping was added to
  calibrate each garage separately; that part was then measured and removed, above.
- **Everything is printed leaving that drive out of the fit**, so a figure beside a
  known floor is a prediction rather than an echo of the label.
- **A separation table per site.** If two floors' ranges overlap within one garage,
  the barometer cannot do this — that is the whole question, and it is now answered on
  the phone rather than by exporting.
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
