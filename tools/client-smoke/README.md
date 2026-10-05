# Client smoke test (headless)

Launches the real NeoForge 1.21.1 **client** in a container with a virtual
display and software GL, auto-joins a prepared world, and screenshots it. It
proves both mods load and register on a real client and that the car and the
layered blocks render. It does not drive the car.

```sh
docker build -t mc-client-smoke tools/client-smoke
# prepare the project directory: this repo's build, run/mods/dynamicterrain-<ver>.jar,
# run/options.txt (onboardAccessibility:false, ...), and a world in run/saves/smoke
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD:/work" -v "$HOME/.gradle:/gradle-home" \
  mc-client-smoke /work/tools/client-smoke/client_smoke.sh
```

Output lands in `out/` (`status.txt`, `latest.log`, `screenshot*.png`). The
build needs `-PquickPlay=<world>`, which `build.gradle` turns into
`--quickPlaySingleplayer`. Not run in CI. Known benign client errors in the
container: no OpenAL device and no narrator library.

`docs/client-smoke-car-and-layers.png` is a screenshot from this harness:
layered dirt (6 and, hanging from above, 4 layers), sand (12), gravel (3) and the
placeholder car.
