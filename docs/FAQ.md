# FAQ

**Is this legal?**
Yes. You are changing settings and package states on hardware you own, over the
debug interface the manufacturer provides. Nothing bypasses a licence check, and
no Amazon code is modified or redistributed.

**Does it survive an Amazon update?**
Partly. Disabled packages usually stay disabled, but an update can re-enable
some and will restore settings. The OTA itself is blocked at DNS by this repo, so
if the filter is running the update never arrives. If you ever unblock it, or
factory-reset, re-run `scripts/all.sh`.

**Does it survive a factory reset?**
No. Nothing does. Re-run the scripts afterwards — that is the whole point of
packaging them.

**Will YouTube still work?**
Yes. The filter deliberately does not touch Google, YouTube or thumbnail hosts,
and `verify.sh` asserts that.

**Will Prime Video work?**
Yes, by default. `configure.sh` defaults to keeping Prime (`PRIME=1`), and
`dnsfilter.sh` strips `aiv-delivery.net` / `aiv-cdn.net` out of the tracker feed
before pushing it. Answer "no" (or set `PRIME=0`) and those hosts stay blocked,
so playback stops. YouTube is unaffected either way.

**Will it remove an app I actually watch?**
No. `configure.sh` asks about the streaming apps this repo would uninstall, one by
one, and anything you say no to is written to `dumbtv.conf` and never touched.
The Amazon system packages it disables (ad service, metrics, Alexa) are listed in
`data/fireos/packages.txt` and can all be restored. You can also add a package to
the keep list later: `./scripts/configure.sh --keep com.spotify.tv.android`.

**I keep Netflix — is anything else broken for it?**
No. Netflix was never in the removal list; only the ad-funded regional apps are.
The filter leaves its hosts alone.

**Can I remove more than the defaults?**
Yes: `./scripts/configure.sh --remove com.spotify.tv.android`, or name the app at
the prompt. Candidates come from `adb shell pm list packages -3`.

**Will the app store still work?**
Yes — the store's API hosts are explicitly not blocked. They are in the "do not
block" list in `data/blocklists/extra-hosts.txt`.

**Will the voice remote still work?**
Buttons and pairing: yes. The microphone: the Alexa app that owns it is disabled,
so voice search does nothing. That is a privacy choice, and it is the first thing
to re-enable if you want it back:

```sh
adb shell pm enable --user 0 com.amazon.aca
adb shell settings put secure alexa_wake_word_disabled 0
```

**Does it need root?**
No. Everything here runs as the `shell` user.

**Do I need the computer every time?**
Only to run the scripts. Daily use is normal: the TV boots, the launcher appears,
apps work. The DNS filter and the package states persist across reboots.

**Can I do it over Wi-Fi, or do I need USB?**
Wi-Fi is fine: `adb connect <tv-ip>:5555`. USB is only useful for the first
connection prompt.

**Why does pressing HOME show Amazon's home screen for a few seconds?**
It should not: dumbtv home lands in about 0.2 s. If Amazon's home shows for ~5 s,
the `min_futurity=0` alarm setting was reset — re-run `scripts/launcher.sh`. See
`docs/GOTCHAS.md`.

**Why your own launcher instead of Projectivy?**
Projectivy is closed source and requests the advertising ID, ad attribution,
install referrer, internet access and package install/delete. A home screen needs
none of that. dumbtv home (`launcher/`) asks only for internet, so the tops row
can read public charts, and uses about half the RAM. Check it yourself:
`aapt2 dump permissions dumbtv-home.apk` prints `android.permission.INTERNET`.

**Something broke. How do I undo it?**
`./scripts/restore.sh`. It re-enables packages, clears app-ops and network rules,
restores the settings in `data/fireos/settings.restore.txt`, and reboots. Apps
uninstalled from `/data` come back from the store.

**Can this brick my TV?**
The scripts never write to `/system`, never remount, never touch the bootloader
and never disable the launcher, settings, input switching, device control or
Bluetooth pairing. `data/fireos/never-disable.txt` is enforced by the script. The
realistic failure mode is a boot that lands on Amazon's home screen — annoying,
not fatal.

**Does it phone home itself?**
The script downloads three things on your computer at run time: the
personalDNSfilter APK, the dumbtv home APK from this repo's release (checked
against a pinned SHA-256), and a blocklist from the HaGeZi repository. The
launcher itself has no internet permission. Nothing is uploaded. The TV downloads nothing for the filter — the
finished list is pushed to it — so the only traffic the filter creates is DNS
lookups, exactly as before.

**Why not just use a Pi-hole?**
Do that instead if your router lets you set DNS — it is less to maintain. This
repo exists because on some TVs the router is not configurable, and the settings
have to live on the device.
