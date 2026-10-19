# dumbtv

Turn an Amazon Fire TV into something that behaves: no ads, no watch tracking, no
OTA, a clean launcher — all applied over ADB from another machine, no root.

Tested end to end on a Fire TV Edition television running Fire OS 7.7.1.7
(Android 9). Fire OS 7/8 streaming devices behave the same way; Android TV keeps
most of it but needs a different package list — see `docs/OTHER-TVS.md`.

**What it will not do:** replace Fire OS, unlock the bootloader, or survive a
factory reset. None of that is possible on these devices, and pretending
otherwise is how people brick their TVs. See `docs/WHY-NOT-REPLACE-OS.md`.

## Add your TV to the catalog

At the end of a session — or any time something surprised you — record it:

```sh
./scripts/contribute.sh
```

It reads the model, OS build and kernel from the TV, then asks only what a robot
cannot know: how developer mode was enabled, where the IP address was shown,
which dialogs blocked automation and what dismissed each one. It writes
`catalog/devices/<id>.yaml`, validates it, and prints the `git` / `gh` commands
for a pull request. No git? `.github/ISSUE_TEMPLATE/device-report.yml` asks the
same questions in a browser.

That file is the part this repo cannot generate: per-model knowledge. Code is
cheap; someone's evening is not.

## It asks what you want to keep

Nothing is decided for you:

```sh
./scripts/configure.sh
```

It goes through the apps this repo would remove (`data/fireos/user-apps.txt`,
with readable names from `data/apps.txt`) and asks, one by one, whether to remove
each. Answer "no" and that app is never touched. It also asks whether you watch
Prime Video — Prime stays installed either way, but if you say yes its video CDN
is left out of the blocklist so playback keeps working — and whether you want the
launcher replaced and the DNS filter installed.

The answers go into `dumbtv.conf` (git-ignored). Re-run it any time to change
your mind; the scripts read that file, so nothing has to be edited by hand.

Ads, telemetry, experiments and Alexa are removed regardless. This step is only
about apps a person might actually watch.

## Reversibility

`scripts/restore.sh` puts back package state, app-ops, network rules and every
setting the pass touched — using the snapshot `preflight.sh` took first, so the
values are the ones this TV actually had, not generic defaults. The snapshot is
taken once: run `preflight.sh` before the first pass, and a later run cannot
overwrite the originals.

Two things are not reversible:

1. **Apps that live in `/data`.** The streaming apps the pass uninstalls (JioHotstar,
   Zee5, JioCinema, SonyLIV, Discovery+, Aaj Tak, Twitch, FastScreen, MX Player)
   have their APKs deleted; they come back from the store, not from a script. The
   keep-list in `configure.sh` exists so you only lose what you agreed to lose.
2. **Anything uninstalled after the fact.** `restore.sh` restores the lists in
   `data/`, so an app removed by hand is still gone.

Everything else — the ~65 disabled packages, the launcher, the DNS filter, the
settings — is undoable, and a factory reset also undoes it (including this repo's
work).

## What it changes

| Layer | Result |
|---|---|
| Packages | ~65 disabled: ACR (screen fingerprinting), ad-ID service, sponsored rows, watch-time analytics, A/B experiment engines, Alexa/mic app, promo screensaver, OTA helpers. Ad-funded streaming apps uninstalled. YouTube and VLC kept. |
| Protected packages | The handful Amazon refuses to disable get their background execution and background data removed instead. |
| Settings | Ad tracking limited, interest-based ads off, app/device usage reporting off, wake word and voice off, promo autoplay off. |
| DNS | personalDNSfilter runs on the TV: Amazon telemetry, Alexa endpoints and the OTA servers answer `127.0.0.1`. YouTube and the app store still resolve. |
| Launcher | [dumbtv home](launcher/README.md), this repo's own launcher, replaces Amazon's home screen — no promo tiles, no sponsored rows. Open source. Internet is used only for the tops row. |

Every step is reversible with `scripts/restore.sh`.

## The launcher

`launcher/` is a home screen written for this repo: your apps, your HDMI inputs,
a clock, and a tops row for the focused app. Plain Java with no libraries. Its
only permission is internet, and only the tops row uses it. HOME lands on it in
about 0.2 s, with no flash of Amazon's home.

It replaced Projectivy, which this repo used to install: Projectivy is closed
source and asks for the advertising ID, ad attribution, install referrer,
internet and package install/delete — far more than a home screen needs.

Just the launcher, without the rest of the pass:

```sh
adb connect <tv-ip>:5555
./launcher/install.sh            # downloads the release APK, checks it, installs it
```

No Java or Android SDK needed: the APK comes from the
[GitHub release](https://github.com/humblemuzzu/dumbtv/releases/latest), and its
SHA-256 is pinned in `launcher/install.sh`. `--build` builds it from source instead.
Details, settings and how the HOME takeover works: `launcher/README.md`.

## Requirements

- A machine with `adb` (macOS, Linux, Windows via WSL/Git Bash).
- The TV on the same network, with **Developer Options → ADB debugging** on
  (Settings → My Fire TV → About → click the device name 7×, then back to
  My Fire TV → Developer Options).
- ~10 minutes. The scripts reboot the TV twice.

## Quickstart

```sh
adb connect <tv-ip>:5555
adb devices                      # must show "device", not "unauthorized"

./scripts/preflight.sh           # read-only: device facts + what would change
./scripts/configure.sh           # asks what to keep, writes dumbtv.conf
./scripts/all.sh                 # the full pass, asks before touching anything
./scripts/verify.sh              # proof: blocked vs still-working domains
```

If anything looks wrong later, `scripts/restore.sh` puts it back.

## Run one piece at a time

| Script | Does | Reboot needed |
|---|---|---|
| `preflight.sh` | Prints device facts, snapshots the current settings, shows the plan. Changes nothing else. | no |
| `discover.sh` | Read-only inventory for a TV with no verified package list: flags likely ads/analytics packages and writes a commented block for `data/<platform>/packages.txt`. | no |
| `configure.sh` | Asks what to keep and writes `dumbtv.conf`. | no |
| `debloat.sh` | Uninstalls/disables packages, applies privacy settings. | yes |
| `lockdown.sh` | App-ops + data-saver rules for protected packages. | no |
| `dnsfilter.sh` | Installs and configures the on-device DNS filter. | yes |
| `launcher.sh` | Installs dumbtv home and takes over the HOME button. | no |
| `verify.sh` | Checks what is blocked and what still works. | no |
| `restore.sh` | Undoes packages, app-ops, network rules and settings. | yes |
| `all.sh` | Runs the above in order. | yes |

Every script takes `--dry-run` to print its commands without running them, and
`-y` to skip confirmation.

## Safety rules the scripts enforce

- A hard-coded **never-disable** list (launcher, settings, TV input/routing,
  device control, Bluetooth pairing, system UI). Disabling those is how a TV ends
  up with a black screen and no working remote.
- Nothing writes to `/system`. No `adb remount`, no `pm uninstall` without
  `--user 0`, no hosts-file editing — Fire OS verifies `/system` with dm-verity
  and modifying it is the documented brick path.
- The DNS filter only runs if the app's VPN consent was pre-granted, and it is
  verified after a reboot before the script reports success.

## Documentation

- `launcher/README.md` — the launcher: install, remote controls, settings file, HOME takeover
- `docs/HOW-IT-WORKS.md` — the five mechanisms and why each is needed
- `docs/GOTCHAS.md` — everything that went wrong while building this
- `docs/OTHER-TVS.md` — Fire OS 6/7/8, Android TV, Tizen, webOS, Roku
- `docs/WHY-NOT-REPLACE-OS.md` — the bootloader/verity wall, and the root exploit
- `docs/FAQ.md` — factory reset, updates, Prime Video, "is this illegal" (it isn't)
- `catalog/` — per-platform menu paths, prompts and quirks, machine-readable

## For AI agents

`AGENTS.md` is the machine-readable contract: preconditions, the user interview,
exact ordering, guardrails, verification and recovery. Point an agent at this
repository and it can run the whole thing without improvising.

`llms.txt` is the short index for agents that look for one, and
`catalog/devices/` holds the part that isn't automatable — developer-mode menu
path, where the IP is shown, which dialogs block a bot — one YAML per platform or
model. Adding a TV to the catalog is how this gets better for the next person.

There is also a skill file at `skills/firetv-debloat/SKILL.md` for agents that
load skills.

## Credits and licences

Scripts, docs and the launcher: MIT (`LICENSE`). Device catalog: CC BY-SA 4.0
(`catalog/LICENSE`).

Installed but not bundled: [personalDNSfilter](https://github.com/IngoZenz/personaldnsfilter)
(GPL), downloaded from its upstream release at run time. The launcher APK is
built from `launcher/` and published on this repo's releases. The Amazon tracker list
is fetched from [HaGeZi DNS blocklists](https://github.com/hagezi/dns-blocklists)
at run time; nothing is vendored, so the blocklist stays current.

Nothing in this repository is Amazon code.
