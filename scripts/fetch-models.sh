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
  [insects]="google/aiy/tfLite/vision-classifier-insects-v1"
)

fetch_with_kagglehub() {
  local handle="$1" out="$2"
  python3 -m pip install --quiet --disable-pip-version-check kagglehub >/dev/null 2>&1 || return 1
  local dir
  dir="$(python3 -c "import kagglehub,sys; print(kagglehub.model_download(sys.argv[1]))" "$handle" 2>/dev/null | tail -1)" || return 1
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

# Report how many common New Zealand birds the bird model knows (labels live in the model's metadata).
if [ -s "$DEST/birds.tflite" ]; then
  labels="$(unzip -p "$DEST/birds.tflite" '*.txt' 2>/dev/null || true)"
  if [ -n "$labels" ]; then
    known=0; missing=()
    for sp in "Prosthemadera novaeseelandiae" "Hemiphaga novaeseelandiae" "Rhipidura fuliginosa" \
              "Petroica longipes" "Anthornis melanura" "Nestor meridionalis" "Nestor notabilis" \
              "Apteryx mantelli" "Porphyrio melanotus" "Porphyrio hochstetteri" "Gerygone igata" \
              "Zosterops lateralis" "Mohoua albicilla" "Acanthisitta chloris" "Hymenolaimus malacorhynchos" \
              "Gallirallus australis" "Strigops habroptilus" "Ninox novaeseelandiae" "Todiramphus sanctus" \
              "Chroicocephalus bulleri" "Phalacrocorax varius" "Turdus merula" "Acridotheres tristis"; do
      if grep -qi "$sp" <<<"$labels"; then known=$((known+1)); else missing+=("$sp"); fi
    done
    echo "Bird model knows $known of 23 common New Zealand birds. Missing: ${missing[*]:-none}"
  fi
fi

# BirdNET V2.4 bird-sound model (CC BY-NC-SA 4.0, K. Lisa Yang Center for Conservation Bioacoustics /
# Chemnitz University of Technology). Audio in: 3 s at 48 kHz. Not fatal if missing.
if [ ! -s "$DEST/birdnet.tflite" ]; then
  zip="$WORK/birdnet.zip"
  if curl -fsSL --retry 3 -o "$zip" "https://zenodo.org/records/15050749/files/BirdNET_v2.4_tflite_fp16.zip?download=1"; then
    rm -rf "$WORK/bn" && mkdir -p "$WORK/bn" && unzip -q "$zip" -d "$WORK/bn"
    echo "BirdNET zip contents:"; (cd "$WORK/bn" && find . -type f | grep -v '/labels/.*_' | head -40)
    model="$(find "$WORK/bn" -name '*.tflite' | grep -vi -e 'mdata' -e 'meta' | head -1)"
    labels="$(find "$WORK/bn" -iname '*labels*.txt' -not -path '*/labels/*' | head -1)"
    [ -z "$labels" ] && labels="$(find "$WORK/bn" -path '*labels*' -name 'en_us.txt' | head -1)"
    [ -z "$labels" ] && labels="$(find "$WORK/bn" -path '*labels*' -name 'en_uk.txt' | head -1)"
    if [ -n "$model" ] && [ -n "$labels" ]; then
      cp "$model" "$DEST/birdnet.tflite"
      cp "$labels" "$DEST/birdnet_labels.txt"
      echo "✓ BirdNET: $(du -h "$DEST/birdnet.tflite" | cut -f1), $(wc -l < "$DEST/birdnet_labels.txt") labels from $(basename "$model") / $(basename "$labels")"
      head -3 "$DEST/birdnet_labels.txt"
      for sp in "Prosthemadera novaeseelandiae" "Anthornis melanura" "Apteryx mantelli" "Petroica longipes" "Strigops habroptilus" "Ninox novaeseelandiae"; do
        grep -qi "$sp" "$DEST/birdnet_labels.txt" && echo "  knows $sp" || echo "  missing $sp"
      done
    else
      echo "::warning::BirdNET zip had no model or labels; bird-song ID will be unavailable"
    fi
  else
    echo "::warning::Could not download BirdNET; bird-song ID will be unavailable"
  fi
fi
