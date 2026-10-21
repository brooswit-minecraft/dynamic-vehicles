# Wheel-contact dust and terrain wear

MINECRAFT-480 keeps three independently controlled effects distinct:

- **Dust** is a server-authoritative Dynamic Atmosphere emission from every wheel that both has a
  current raycast contact and carried positive suspension load in the current physics tick. Ordinary
  driving dust uses authoritative chassis speed; skids and burnouts use that wheel's authoritative
  slip speed. A raycast miss, zero-load wheel, or airborne car emits no dust.
- **Terrain wear** remains the existing `SableCarBody` -> `SlipReporter.report` path. It uses the
  actual contacted block, paired load and worst per-wheel slip once per wheel per tick; Dynamic
  Terrain decides material eligibility, protection/unbreakable gates, and the resulting erosion.
  `wearEnabled` and `wearMultiplier` remain its controls. Dust never creates a second erosion report.
- **Exhaust** remains a rear vehicle-local source owned by the separate throttle-exhaust feature; it
  does not share the wheel-contact dust settings.

All values below live in the server `CarConfig`, written to `dynamicvehicles-server.toml`. They are
read on each emission interval, so the active server config governs all vehicles rather than a
per-car snapshot. NeoForge's config reload lifecycle is outside this feature and was not exercised
by these tests; edit/reload timing should follow the server's normal config procedure rather than
assuming a raw file edit applies immediately.

| Key | Unit | Default | Range | Effect |
|---|---:|---:|---:|---|
| `dustEnabled` | boolean | `true` | — | Enables all wheel-contact dust; `false` is quiet without changing erosion or exhaust. |
| `dustMultiplier` | multiplier | 2.5 | 0.0–10.0 | Scales each qualifying wheel's dust; `0` disables output. |
| `dustSpeedStart` | m/s | 5.0 | 0.0–100.0 | Actual chassis-speed threshold for normal rolling dust. |
| `dustSlipStart` | m/s | 3.0 | 0.0–100.0 | Actual per-wheel slip threshold for skid/burnout dust, including near-zero chassis speed. |
| `dustMaxAmount` | atmosphere amount/wheel/emission interval | 32 | 1–64 | Hard cap for one wheel's output in one interval. |
| `smokeEmitTicks` | game ticks | 6 | 5–200 | Existing shared effect cadence. Dust is evaluated once per car interval, never per physics sub-step. |

At the defaults the speed/slip thresholds and 2.5 multiplier preserve the former numeric dust curve,
but dust now originates at loaded wheel contacts instead of one rear-block approximation. Four loaded
wheels can therefore visibly emit independently; `dustMaxAmount` caps each source rather than hiding
wheel contact information behind a single global source.

## Validated boundary

Focused math tests cover zero scale, speed-only dust, stationary slip-only dust and the per-wheel cap.
`CarWheelDustGameTests` observes the production `AtmosphereCompat.dust` delivery path and verifies
that only loaded wheel contacts emit, including an unloaded raycast contact as the lost-contact case.
Existing terrain tests remain responsible for Dynamic Terrain's material/protection/unbreakable and
actual erosion-result gates; this feature deliberately does not alter those authority checks.

The following still needs runtime play validation: visual density across trophy truck, truck, base car,
muscle car, Indy and rally; Dynamic Atmosphere's client rendering/allow-list behavior; and a live
server's config reload behavior. No live atmosphere allow-list or server setting is changed here.
