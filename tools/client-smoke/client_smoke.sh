#!/bin/bash
# runs inside the container: Xvfb + dev client auto-joining the world, screenshot, log check
set -u
export HOME=/tmp/h; mkdir -p $HOME
export GRADLE_USER_HOME=/gradle-home
Xvfb :99 -screen 0 1280x720x24 +extension GLX +render -noreset >/work/out/xvfb.log 2>&1 &
export DISPLAY=:99
sleep 3
cd /work
(./gradlew runClient -PquickPlay=smoke --no-daemon > /work/out/client-gradle.log 2>&1) &
for i in $(seq 1 360); do
  if grep -q "joined the game" run/logs/latest.log 2>/dev/null; then echo "joined at ${i}x5s" > /work/out/status.txt; break; fi
  if grep -qE "Crash report saved|ModLoadingException|Exception in thread \"main\"" /work/out/client-gradle.log 2>/dev/null; then echo "crashed during startup" > /work/out/status.txt; break; fi
  sleep 5
done
sleep 20
import -display :99 -window root /work/out/screenshot1.png 2>/work/out/import.log || true
# look around: turn the camera a little for a second shot
xdotool mousemove 640 360 2>/dev/null; sleep 2
import -display :99 -window root /work/out/screenshot2.png 2>>/work/out/import.log || true
cp run/logs/latest.log /work/out/latest.log 2>/dev/null
pkill -f runClient; pkill -f "net.neoforged" ; sleep 3
echo done >> /work/out/status.txt
