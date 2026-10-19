#!/usr/bin/env bash
# Read-only. Prints what the TV is and what the full pass would change.
# Usage: ./scripts/preflight.sh [--dry-run]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
parse_flags "$@"
need_device
mkdir -p "$REPORT"

step "device"
note "serial        $SERIAL"
note "manufacturer  $(dev1 getprop ro.product.manufacturer)"
note "model         $(dev1 getprop ro.product.model)"
note "build         $(dev1 getprop ro.build.display.id)"
note "android       $(dev1 getprop ro.build.version.release) (sdk $(dev1 getprop ro.build.version.sdk))"
note "kernel        $(dev1 uname -r)"
note "abi           $(dev1 getprop ro.product.cpu.abi)"
note "verified boot $(dev1 getprop ro.boot.verifiedbootstate)  (1 = locked, cannot be changed)"
note "gateway       $(gateway)"

if is_fireos; then
  note "platform      Fire OS"
else
  note "platform      NOT Amazon hardware — read docs/OTHER-TVS.md before running debloat.sh"
fi

step "current state"
note "packages      $(dev 'pm list packages' | wc -l | tr -d ' ') installed, $(dev 'pm list packages -d' | wc -l | tr -d ' ') disabled"
note "third-party   $(dev 'pm list packages -3' | sed 's/package://' | grep -vc overlay) apps"
note "home screen   $(dev 'dumpsys activity activities' | grep -m1 mResumedActivity | sed 's/.*u0 //;s/}.*//' | awk -F/ '{print $1}')"
note "free ram      $(dev 'dumpsys meminfo' | sed -n 's/.*Free RAM: *\([0-9,]*K\).*/\1/p' | head -1)"
note "dns filter    $(present dnsfilter.android && echo installed || echo 'not installed')"
note "launcher      $(present tv.dumbtv.home && echo 'dumbtv home installed' || echo 'not installed')"

step "the pass would"
note "disable $(($(list_of "$DATA/fireos/packages.txt" | wc -l | tr -d ' ') - 0)) Amazon packages, minus anything in never-disable.txt"
note "remove $(list_of "$DATA/fireos/user-apps.txt" | wc -l | tr -d ' ') third-party apps"
note "deny background work + data to $(list_of "$DATA/fireos/protected.txt" | wc -l | tr -d ' ') protected packages"
note "apply $(list_of "$DATA/fireos/settings.txt" | wc -l | tr -d ' ') privacy settings"
note "install a DNS filter that blocks Amazon telemetry, Alexa and the OTA servers"
note "replace the home screen with dumbtv home (this repo's own launcher, no ads)"
note "reboot twice (about 5 minutes)"

step "not touched, ever"
note "/system (dm-verity), the bootloader, the launcher package itself, settings, TV input, device control, Bluetooth pairing"
note "see data/fireos/never-disable.txt"

step "snapshot before anything changes"
if [[ $DRY_RUN == 0 ]]; then
  # Only the first run counts: later runs would snapshot the already-tightened
  # values and destroy the originals restore.sh needs.
  if [[ -f $REPORT/settings-before-secure.txt ]]; then
    note "settings snapshot already exists, keeping the original"
  else
    dev 'settings list secure' > "$REPORT/settings-before-secure.txt" 2>/dev/null || true
    dev 'settings list global' > "$REPORT/settings-before-global.txt" 2>/dev/null || true
    note "saved to report/ (git-ignored) so restore.sh can put the exact values back"
  fi
else
  note "[dry-run] would snapshot secure and global settings into report/"
fi

step "next"
note "./scripts/all.sh            full pass (asks first)"
note "./scripts/debloat.sh --dry-run   see the exact package commands"
