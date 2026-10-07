#!/usr/bin/env bash
#
# Downloads everything the app needs to work 100% offline and puts it where the
# Android build expects it:
#
#   app/libs/sherpa-onnx-<ver>.aar                     offline TTS runtime (JNI + Kotlin API)
#   app/src/main/assets/font.ttf                       caption font
#   app/src/main/assets/vits-piper-en_US-ljspeech-medium/
#       en_US-ljspeech-medium.onnx                     Piper voice model (63 MB)
#       en_US-ljspeech-medium.onnx.json
#       tokens.txt
#       espeak-ng-data/...                             phonemizer data (355 files)
#
# The files come from the Shorts-Gen GitHub release, so the repository itself stays
# small (GitHub's web uploader rejects files > 25 MB anyway) while the *built APK*
# contains everything and never touches the network.
#
# Usage:  bash scripts/prepare_assets.sh
# Safe to re-run: files that are already there are not downloaded again.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# ---------------------------------------------------------------- configuration
SHERPA_ONNX_VERSION="${SHERPA_ONNX_VERSION:-1.13.8}"
SHERPA_AAR="sherpa-onnx-${SHERPA_ONNX_VERSION}.aar"
SHERPA_AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_ONNX_VERSION}/${SHERPA_AAR}"

RELEASE_BASE="${SHORTSGEN_RELEASE_BASE:-https://github.com/vishala5000/Shorts-Gen/releases/download/assets}"

MODEL_DIR_NAME="vits-piper-en_US-ljspeech-medium"
MODEL_ONNX="en_US-ljspeech-medium.onnx"
MODEL_JSON="en_US-ljspeech-medium.onnx.json"

LIBS_DIR="app/libs"
ASSETS_DIR="app/src/main/assets"
MODEL_ASSETS_DIR="${ASSETS_DIR}/${MODEL_DIR_NAME}"
WORK_DIR="$(mktemp -d)"

trap 'rm -rf "$WORK_DIR"' EXIT

mkdir -p "$LIBS_DIR" "$ASSETS_DIR" "$MODEL_ASSETS_DIR"

# ---------------------------------------------------------------- helpers
log()  { printf '\033[1;34m[prepare]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[prepare] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

download() { # download <url> <dest>
    local url="$1" dest="$2"
    if [[ -s "$dest" ]]; then
        log "already there: $(basename "$dest")"
        return 0
    fi
    log "downloading $(basename "$dest")"
    if command -v curl >/dev/null 2>&1; then
        curl -fL --retry 5 --retry-delay 3 --retry-all-errors -C - -o "$dest.part" "$url"
    elif command -v wget >/dev/null 2>&1; then
        wget --tries=5 -O "$dest.part" "$url"
    else
        fail "neither curl nor wget is available"
    fi
    mv "$dest.part" "$dest"
}

extract_bz2() { # extract_bz2 <archive> <dest-dir>
    local archive="$1" dest="$2"
    mkdir -p "$dest"
    if tar -xjf "$archive" -C "$dest" 2>/dev/null; then
        return 0
    fi
    # bzip2 binary missing (some minimal images) -> fall back to python
    command -v python3 >/dev/null 2>&1 || fail "cannot extract $archive (need bzip2 or python3)"
    python3 - "$archive" "$dest" <<'PY'
import sys, tarfile
with tarfile.open(sys.argv[1], "r:bz2") as t:
    t.extractall(sys.argv[2])
PY
}

file_size() { # human readable size
    if stat -c %s "$1" >/dev/null 2>&1; then
        stat -c %s "$1"
    else
        stat -f %z "$1"
    fi
}

# ---------------------------------------------------------------- 1) TTS runtime
download "$SHERPA_AAR_URL" "${LIBS_DIR}/${SHERPA_AAR}"

# ---------------------------------------------------------------- 2) font
download "${RELEASE_BASE}/font.ttf" "${ASSETS_DIR}/font.ttf"

# ---------------------------------------------------------------- 3) voice model
if [[ -s "${MODEL_ASSETS_DIR}/${MODEL_ONNX}" && -s "${MODEL_ASSETS_DIR}/tokens.txt" ]]; then
    log "already there: ${MODEL_DIR_NAME} (model + tokens)"
else
    download "${RELEASE_BASE}/${MODEL_DIR_NAME}.tar.bz2" "${WORK_DIR}/model.tar.bz2"
    log "extracting ${MODEL_DIR_NAME}.tar.bz2"
    extract_bz2 "${WORK_DIR}/model.tar.bz2" "${WORK_DIR}/model"

    extracted="$(find "${WORK_DIR}/model" -maxdepth 2 -name "$MODEL_ONNX" -print -quit)"
    [[ -n "$extracted" ]] || fail "could not find ${MODEL_ONNX} inside the archive"
    src_dir="$(dirname "$extracted")"

    cp -f "${src_dir}/${MODEL_ONNX}" "${MODEL_ASSETS_DIR}/${MODEL_ONNX}"
    [[ -f "${src_dir}/tokens.txt" ]] && cp -f "${src_dir}/tokens.txt" "${MODEL_ASSETS_DIR}/tokens.txt"
    [[ -f "${src_dir}/${MODEL_JSON}" ]] && cp -f "${src_dir}/${MODEL_JSON}" "${MODEL_ASSETS_DIR}/${MODEL_JSON}"

    # espeak-ng-data ships inside the model archive
    if [[ -d "${src_dir}/espeak-ng-data" ]]; then
        log "extracting espeak-ng-data (bundled with the model)"
        mkdir -p "${MODEL_ASSETS_DIR}/espeak-ng-data"
        cp -rf "${src_dir}/espeak-ng-data/." "${MODEL_ASSETS_DIR}/espeak-ng-data/"
    fi
fi

# ---------------------------------------------------------------- 4) phonemizer data
if [[ ! -s "${MODEL_ASSETS_DIR}/espeak-ng-data/phontab" ]]; then
    download "${RELEASE_BASE}/espeak-ng-data.tar.bz2" "${WORK_DIR}/espeak.tar.bz2"
    log "extracting espeak-ng-data.tar.bz2"
    extract_bz2 "${WORK_DIR}/espeak.tar.bz2" "${WORK_DIR}/espeak"
    found="$(find "${WORK_DIR}/espeak" -maxdepth 2 -type d -name espeak-ng-data -print -quit)"
    [[ -n "$found" ]] || fail "espeak-ng-data not found in the archive"
    mkdir -p "${MODEL_ASSETS_DIR}/espeak-ng-data"
    cp -rf "${found}/." "${MODEL_ASSETS_DIR}/espeak-ng-data/"
fi

# standalone copies of the model files, only needed if the archive did not have them
if [[ ! -s "${MODEL_ASSETS_DIR}/${MODEL_ONNX}" ]]; then
    download "${RELEASE_BASE}/${MODEL_ONNX}" "${MODEL_ASSETS_DIR}/${MODEL_ONNX}"
fi
if [[ ! -s "${MODEL_ASSETS_DIR}/${MODEL_JSON}" ]]; then
    download "${RELEASE_BASE}/${MODEL_JSON}" "${MODEL_ASSETS_DIR}/${MODEL_JSON}"
fi

# ---------------------------------------------------------------- 5) verify
log "verifying"
required=(
    "${LIBS_DIR}/${SHERPA_AAR}"
    "${ASSETS_DIR}/font.ttf"
    "${MODEL_ASSETS_DIR}/${MODEL_ONNX}"
    "${MODEL_ASSETS_DIR}/${MODEL_JSON}"
    "${MODEL_ASSETS_DIR}/tokens.txt"
    "${MODEL_ASSETS_DIR}/espeak-ng-data/phontab"
    "${MODEL_ASSETS_DIR}/espeak-ng-data/phondata"
    "${MODEL_ASSETS_DIR}/espeak-ng-data/phonindex"
    "${MODEL_ASSETS_DIR}/espeak-ng-data/intonations"
    "${MODEL_ASSETS_DIR}/espeak-ng-data/en_dict"
)
for f in "${required[@]}"; do
    [[ -s "$f" ]] || fail "missing or empty: $f"
done
lang_file="$(find "${MODEL_ASSETS_DIR}/espeak-ng-data/lang" -type f -name 'en*' 2>/dev/null | head -1)"
[[ -n "$lang_file" ]] || fail "no English voice file under espeak-ng-data/lang"

espeak_files="$(find "${MODEL_ASSETS_DIR}/espeak-ng-data" -type f | wc -l | tr -d ' ')"
total_bytes="$(du -sk "$ASSETS_DIR" | cut -f1)"

log "sherpa-onnx AAR : $(file_size "${LIBS_DIR}/${SHERPA_AAR}") bytes"
log "voice model     : $(file_size "${MODEL_ASSETS_DIR}/${MODEL_ONNX}") bytes"
log "espeak-ng-data  : ${espeak_files} files"
log "assets total    : $((total_bytes / 1024)) MB"
log "OK - you can now run: ./gradlew assembleDebug"
