Android Auto vehicle data probe. Install on a **phone**, not the car.

| Asset | Use |
|---|---|
| `*-release.apk` | Start here |
| `*-debug.apk` | Fallback if the app never appears in the car launcher |

After installing, the app still has to be allowed through Android Auto: open the
Android Auto settings on the phone, tap the version 10 times to unlock
**Developer settings**, then enable **Unknown sources**. Launching the app on the
phone only shows instructions — the probe itself runs on the car display.

The two builds differ in host validation: debug accepts any host
(`ALLOW_ALL_HOSTS_VALIDATOR`), release accepts only `hosts_allowlist_sample`. If
the release build installs but never shows up on the car screen, that is the first
thing to suspect, and the debug APK rules it in or out.

Both are signed with the throwaway `testkey.jks` in the repo, so they install over
each other and over local builds.

The AAOS module is not published here: it requires the
`android.hardware.type.automotive` feature and will not install on a phone.
