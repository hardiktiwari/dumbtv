#!/usr/bin/env bash
# Installs dumbtv home (this repo's own launcher, launcher/) and gives it the
# HOME button. Fire OS ignores "default home" and protects Amazon's launcher, so
# the takeover runs through a small accessibility service; launcher/README.md has
# the details. Usage: ./scripts/launcher.sh [--dry-run] [-y]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
parse_flags "$@"
need_device
load_conf

if [[ $LAUNCHER == 0 ]]; then
  note "LAUNCHER=0 in dumbtv.conf — keeping Amazon's home screen. Re-run ./scripts/configure.sh to change that."
  exit 0
fi

PKG=tv.dumbtv.home

step "plan"
note "install dumbtv home: open source, internet only for the tops row (no ad ID)"
note "the prebuilt APK comes from this repo's GitHub release and is checksum-verified"
note "take over HOME: accessibility service + settings global alarm_manager_constants min_futurity=0"
note "Amazon's launcher stays installed: hold HOME for Fire OS's menu, or Options > Open Amazon's home screen"
if [[ $DRY_RUN == 1 ]]; then
  note "[dry-run] ADB=$ADB ADB_SERIAL=$SERIAL launcher/install.sh"
  exit 0
fi
confirm "Replace the home screen on $SERIAL?" || die "cancelled"

ADB=$ADB ADB_SERIAL=$SERIAL "$ROOT/launcher/install.sh" || die "launcher install failed (see above)"

sleep 3
home=$(dev 'dumpsys activity activities' | grep -m1 mResumedActivity | sed 's/.*u0 //;s/}.*//')
if printf '%s' "$home" | grep -q "$PKG"; then
  note "home screen is dumbtv home"
else
  note "home screen is $home"
  note "press HOME once or run this script again; see docs/GOTCHAS.md if it will not stick"
  exit 1
fi
