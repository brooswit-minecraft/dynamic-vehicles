#!/usr/bin/env python3
"""Disposable CI only. Child process groups are the only processes terminated.

MINECRAFT-493: exact-head coinstalled acceptance -- unlike acceptance.py's full car/boat/HUD
baseline (which this intentionally does not wait for), this only waits on
CourierCraftAcceptance's own, much shorter check (see that class's javadoc): both mods actually
mod-loaded together, Dispatcher's and Pathfinder's simulated markers sharing real
hud.MissionWaypointLayout state without overlapping, and the Dispatcher slot releasing on clear.

Usage: coinstalled_acceptance.py <path-to-courier-craft-jar>
"""
import json
import os
import sys
from pathlib import Path
import signal
import subprocess
import time

if len(sys.argv) != 2:
    raise SystemExit(f"usage: {sys.argv[0]} <path-to-courier-craft-jar>")
courier_craft_jar = Path(sys.argv[1]).resolve()
if not courier_craft_jar.is_file():
    raise SystemExit(f"courier-craft jar not found: {courier_craft_jar}")

root = Path(__file__).resolve().parents[2]
# build.gradle's clientAcceptance block hardcodes these two directory names (not parametrized);
# reused as-is rather than inventing new ones Gradle would never actually write into. Safe: each
# CI job gets its own fresh runner, so this never collides with the separate, unmodified
# client-acceptance job that also uses these same directory names.
server = root / 'run-acceptance-server'
client = root / 'run-acceptance-client'
for path in (server, client):
    path.mkdir(exist_ok=False)
(server / 'eula.txt').write_text('eula=true\n')
(server / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25565\nonline-mode=false\nenforce-secure-profile=false\nlevel-name=coinstalled-disposable\nlevel-type=minecraft:flat\ngamemode=creative\nspawn-protection=0\nview-distance=4\nsimulation-distance=4\nmax-players=1\n')
(client / 'options.txt').write_text('onboardAccessibility:false\nrenderDistance:4\nsimulationDistance:4\nmaxFps:30\npauseOnLostFocus:false\n')
processes = []
logs = []
try:
    def start(task, output, *args):
        log = open(root / output, 'w')
        logs.append(log)
        process = subprocess.Popen(
            # gradle.properties' version=0.0.0 is a release-gate placeholder, never the real
            # shipped version (see that file's own comment) -- a dev run that doesn't pass
            # -Pmod_version reports "0.0.0" to FML, which fails Courier Craft's declared
            # versionRange="[0.82.0,)" on this optional dependency. MINECRAFT-493 review (comment
            # 52567, finding 1): an earlier version of this pinned the REAL released number
            # (0.82.1), which is misleading -- that release is pre-split and would actually
            # conflict with Courier Craft's own Dispatcher registrations; this run's code is this
            # PR's unreleased head, not that release. Use an obviously-synthetic, unmistakably
            # unreleased version instead: it exists only to satisfy this dev build's own version
            # *range* check, never to claim a real release. The real compatibility gate Courier
            # Craft actually relies on is DynamicVehiclesCompatibility's runtime marker-class
            # handshake (version-independent by design -- see that class and
            # DynamicVehiclesBridgeSchemaTest), not this declared range.
            ['./gradlew', task, '-PclientAcceptance', f'-PcourierCraftJar={courier_craft_jar}',
             '-Pmod_version=999.0.0-ci-unreleased',
             '--no-daemon', '-Dorg.gradle.jvmargs=-Xmx1G',
             *args],
            cwd=root, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        processes.append(process)
        return process

    server_process = start('runServer', 'coinstalled-acceptance-server.log')
    deadline = time.monotonic() + 360
    while time.monotonic() < deadline:
        text = (root / 'coinstalled-acceptance-server.log').read_text(errors='replace')
        if 'Done (' in text:
            break
        if server_process.poll() is not None:
            raise RuntimeError('server exited before readiness')
        time.sleep(2)
    else:
        raise RuntimeError('server startup timeout')

    client_process = start('runClient', 'coinstalled-acceptance-client.log', '-PquickPlayMp=127.0.0.1:25565')
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        if (client / 'courier-craft-acceptance-failure.txt').exists():
            raise RuntimeError((client / 'courier-craft-acceptance-failure.txt').read_text())
        if (client / 'courier-craft-acceptance-result.json').exists():
            break
        if client_process.poll() is not None:
            raise RuntimeError('client exited before reporting a coinstalled acceptance result')
        time.sleep(2)
    else:
        raise RuntimeError('coinstalled acceptance timeout -- CourierCraftAcceptance never reported a result')

    result = json.loads((client / 'courier-craft-acceptance-result.json').read_text())
    if result.get('passed') is not True:
        raise RuntimeError('client did not report coinstalled acceptance success')
    print(json.dumps(result))
finally:
    for process in reversed(processes):
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
    for log in logs:
        log.close()
    for path in (root / 'coinstalled-acceptance-client.log', client / 'logs/latest.log', root / 'coinstalled-acceptance-server.log'):
        if path.exists():
            print('ACCEPTANCE_DIAGNOSTIC ' + str(path), flush=True)
            print('\n'.join(path.read_text(errors='replace').splitlines()[-100:]), flush=True)
