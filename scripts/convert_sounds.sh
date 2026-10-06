#!/bin/bash
# Converts the source car sounds (mono WAV) to the mono OGG Vorbis files Minecraft plays.
# Usage: scripts/convert_sounds.sh <source-dir> [ffmpeg]   (source-dir has engine/, impact/, tire/)
set -e
SRC=${1:?source directory}; FFMPEG=${2:-ffmpeg}
OUT="$(dirname "$0")/../src/main/resources/assets/dynamicvehicles/sounds/car"
for f in engine/engine0 engine/engine1 engine/engine3 engine/start engine/stop \
         impact/impact0 impact/impact1 impact/impact2 impact/impact3 \
         tire/rough tire/smooth tire/skid0 tire/skid1 tire/snow; do
  mkdir -p "$OUT/$(dirname "$f")"
  "$FFMPEG" -hide_banner -loglevel error -y -i "$SRC/$f.wav" -ac 1 -c:a libvorbis -q:a 4 "$OUT/$f.ogg"
done
