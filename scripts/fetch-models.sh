#!/usr/bin/env bash
# Downloads Google's AIY on-device classifiers (Apache 2.0) from Kaggle Models into the app's assets.
# Used by CI; you can also run it locally before building. Missing models are not fatal: the app
# then falls back to online identification.
set -uo pipefail

DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/models"
mkdir -p "$DEST"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# name -> Kaggle model handle (owner/model/framework/variation)
declare -A HANDLES=(
  [birds]="google/aiy/tfLite/vision-classifier-birds-v1"
  [plants]="google/aiy/tfLite/vision-classifier-plants-v1"
)

fetch_with_kagglehub() {
  local handle="$1" out="$2"
  python3 -m pip install --quiet --disable-pip-version-check kagglehub >/dev/null 2>&1 || return 1
  local dir
  dir="$(python3 -c "import kagglehub,sys; print(kagglehub.model_download(sys.argv[1]))" "$handle" 2>/dev/null)" || return 1
  local f
  f="$(find "$dir" -name '*.tflite' | head -1)"
  [ -n "$f" ] && cp "$f" "$out"
}

fetch_with_curl() {
  local handle="$1" out="$2"
  for v in 3 2 1; do
    local tgz="$WORK/m.tgz"
    if curl -fsSL -o "$tgz" "https://www.kaggle.com/api/v1/models/$handle/$v/download"; then
      rm -rf "$WORK/x" && mkdir -p "$WORK/x"
      if tar -xzf "$tgz" -C "$WORK/x" 2>/dev/null; then
        local f
        f="$(find "$WORK/x" -name '*.tflite' | head -1)"
        [ -n "$f" ] && cp "$f" "$out" && return 0
      elif head -c 8 "$tgz" | grep -q "TFL3"; then
        cp "$tgz" "$out" && return 0
      fi
    fi
  done
  return 1
}

for name in "${!HANDLES[@]}"; do
  out="$DEST/$name.tflite"
  if [ -s "$out" ]; then echo "✓ $name already present"; continue; fi
  if fetch_with_kagglehub "${HANDLES[$name]}" "$out" || fetch_with_curl "${HANDLES[$name]}" "$out"; then
    echo "✓ $name: $(du -h "$out" | cut -f1)"
  else
    rm -f "$out"
    echo "::warning::Could not download the $name model; the app will build without it"
  fi
done
