#!/usr/bin/env bash
# Downloads the Supertonic 3 voice model (Open RAIL-M, (c) Supertone Inc.) from its official
# Hugging Face repository into supertonic_assets/, pinned to one revision, and verifies every file's SHA-256.
# Usage: scripts/get_models.sh   (run from anywhere; needs curl and sha256sum or shasum)
set -euo pipefail

REPO="supertone-oss-archive/supertonic-3"
REV="aafc6e32416a594460b32413efc49d7fe4ce6d46"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/supertonic_assets/src/main/assets/supertonic"

FILES="
c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db onnx/duration_predictor.onnx
c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff onnx/text_encoder.onnx
883ac868ea0275ef0e991524dc64f16b3c0376efd7c320af6b53f5b780d7c61c onnx/vector_estimator.onnx
085de76dd8e8d5836d6ca66826601f615939218f90e519f70ee8a36ed2a4c4ba onnx/vocoder.onnx
42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09 onnx/tts.json
9bf7346e43883a81f8645c81224f786d43c5b57f3641f6e7671a7d6c493cb24f onnx/unicode_indexer.json
bbdec6ee00231c2c742ad05483df5334cab3b52fda3ba38e6a07059c4563dbc2 voice_styles/F1.json
7c722c6a72707b1a77f035d67f0d1351ba187738e06f7683e8c72b1df3477fc6 voice_styles/F2.json
12f6ef2573baa2defa1128069cb59f203e3ab67c92af77b42df8a0e3a2f7c6ab voice_styles/F3.json
c2fa764c1225a76dfc3e2c73e8aa4f70d9ee48793860eb34c295fff01c2e032b voice_styles/F4.json
45966e73316415626cf41a7d1c6f3b4c70dbc1ba2bee5c1978ef0ce33244fc8d voice_styles/F5.json
e35604687f5d23694b8e91593a93eec0e4eca6c0b02bb8ed69139ab2ea6b0a5b voice_styles/M1.json
b76cbf62bac707c710cf0ae5aba5e31eea1a6339a9734bfae33ab98499534a50 voice_styles/M2.json
ea1ac35ccb91b0d7ecad533a2fbd0eec10c91513d8951e3b25fbba99954e159b voice_styles/M3.json
ca8eefad4fcd989c9379032ff3e50738adc547eeb5e221b82593a6d7b3bac303 voice_styles/M4.json
dd22b92740314321f8ae11c5e87f8dd60d060f15dd3a632b5adf77f471f77af2 voice_styles/M5.json
"

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

echo "$FILES" | while read -r want path; do
  [ -z "$path" ] && continue
  out="$DEST/$path"
  if [ -f "$out" ] && [ "$(sha256 "$out")" = "$want" ]; then
    echo "ok (cached)  $path"; continue
  fi
  mkdir -p "$(dirname "$out")"
  echo "downloading  $path"
  curl -fsSL --retry 3 -o "$out.part" "https://huggingface.co/$REPO/resolve/$REV/$path"
  got="$(sha256 "$out.part")"
  if [ "$got" != "$want" ]; then
    rm -f "$out.part"
    echo "SHA-256 mismatch for $path: expected $want, got $got" >&2
    exit 1
  fi
  mv "$out.part" "$out"
  echo "ok           $path"
done

echo "Supertonic 3 model ready in $DEST"

# bundletool (Apache-2.0, Google), used to install the debug bundle with its voice-model pack.
BT_VER="1.18.3"
BT_SHA="a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29"
BT="$ROOT/scripts/bundletool.jar"
if [ -f "$BT" ] && [ "$(sha256 "$BT")" = "$BT_SHA" ]; then
  echo "ok (cached)  bundletool $BT_VER"
else
  echo "downloading  bundletool $BT_VER"
  curl -fsSL --retry 3 -o "$BT.part" "https://github.com/google/bundletool/releases/download/$BT_VER/bundletool-all-$BT_VER.jar"
  got="$(sha256 "$BT.part")"
  if [ "$got" != "$BT_SHA" ]; then
    rm -f "$BT.part"
    echo "SHA-256 mismatch for bundletool: expected $BT_SHA, got $got" >&2
    exit 1
  fi
  mv "$BT.part" "$BT"
  echo "ok           bundletool $BT_VER"
fi
echo "License: Open RAIL-M, see supertonic_assets/LICENSE-Supertonic-OpenRAIL-M.txt"
