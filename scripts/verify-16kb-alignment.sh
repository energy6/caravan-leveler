#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $(basename "$0") APK" >&2
  exit 2
fi

apk=$1
if [[ ! -f "$apk" ]]; then
  echo "APK does not exist: $apk" >&2
  exit 2
fi

sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [[ -z "$sdk_root" && -d "$HOME/Library/Android/sdk" ]]; then
  sdk_root="$HOME/Library/Android/sdk"
fi
if [[ -z "$sdk_root" || ! -d "$sdk_root/build-tools" ]]; then
  echo "Set ANDROID_SDK_ROOT to an Android SDK containing Build Tools 35 or newer." >&2
  exit 2
fi

zipalign=${ZIPALIGN:-}
if [[ -z "$zipalign" ]]; then
  while IFS= read -r candidate; do
    zipalign=$candidate
  done < <(find "$sdk_root/build-tools" -mindepth 2 -maxdepth 2 -type f -name zipalign | sort)
fi
if [[ -z "$zipalign" || ! -x "$zipalign" ]]; then
  echo "Could not find an executable zipalign in $sdk_root/build-tools." >&2
  exit 2
fi

"$zipalign" -c -P 16 4 "$apk"
echo "Verified 16 KiB ZIP alignment: $apk"
python3 "$(dirname "$0")/check_elf_alignment.py" "$apk"
