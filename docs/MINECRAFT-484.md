# MINECRAFT-484 driving HUD

Frozen section 8 only. Health becomes car health, XP becomes RPM, food becomes speed
from zero to the vehicle's intended top speed, and armor becomes actual tire slip.
The four labeled bars occupy the corresponding lower HUD positions. No player stats mutate.

The server synchronizes health, `spec.maxSpeed()` (the actual governor target), and raw tire
slip along with existing driving speed/RPM. Slip excludes the audio handbrake/reverse override.
Implementation defaults: RPM full scale is the existing 6500-RPM limiter; slip full scale is
4 m/s, a reviewable display choice. Speed/health/RPM/slip are clamped to their display bounds;
reverse speed uses magnitude. Protocol 2 prevents old peers misreading the changed entity data.

Only the local controlling passenger of a ready rigid-body car gets the replacement. Each frame
checks the current vehicle/driver, hidden GUI and spectator state. Dismount, seat change, death,
disconnect/world change restore vanilla rendering without saved HUD state. The vanilla XP level
and mount health are suppressed along with the four replaced layers to avoid duplicate displays.

The production entity-data GameTest also checks restored car health, server top-speed scale,
and separation of tire slip from audio slip. Canceled layers reserve space for vanilla air/status
overlays. Tests cover visibility and normalized telemetry bounds, including reverse/overspeed/invalid
values. Existing real client acceptance remains enabled in PR CI; it does not yet establish all
new HUD visuals. Local Gradle cannot start because `services.gradle.org` cannot resolve.
Exact-head CI evidence is in the PR. No installation, live-world change or manual playtest occurred.

Manual acceptance still required: damaged/full car vs player health; idle/shift/limiter RPM;
stationary/reverse/top-speed/overspeed; rolling vs actual tire slip; passenger vs driver; exit,
seat swap, F1, spectator, death, disconnect and dimension change. Check nondefault server top-speed
configuration against a client with different local defaults. Confirm ordinary player bars return.
This does not change physics or MINECRAFT-478 caster/roll-center scope.

## Review corrections: layout and feature-specific acceptance

RPM now occupies `[H-34, H-24)`, leaving two clear scaled pixels before the hotbar
at `H-22`. Health/speed occupy `[H-46, H-36)` and slip `[H-58, H-48)`.
`DrivingHudLayout` supplies these actual renderer bounds and reservation constants.
The selected-item layer reserves at least 70 pixels in survival, 84 in creative to
compensate vanilla's +14 creative offset. Its 9-pixel label ends at `H-61`, above
all gauges even when the name spans the full HUD width. Air starts at `H-58` on
the right and remains clear of speed.

`DrivingHudLayoutTest` verifies non-overlap across minimum/odd and common scaled
viewports, both modes and a full-width selected-item label. The test-only real
client `HudAcceptanceChecks` calls the actual production `RenderGuiLayerEvent.Pre`
handler with the current player/mount, both game modes, GUI scales 1–4, and F1
on/off. It verifies cancellation, unchanged reservations after exit, long-name
bounds using actual font metrics, and mount/exit/re-entry coverage. Receipts are
`CLIENT_ACCEPTANCE_HUD_PASS`; these probes do not claim pixel screenshot or visual
quality approval. The original input/camera/dust harness still runs separately.

The existing wall-pinned D1 GameTest now transfers real nonzero physics tire slip
into a replica's entity data and checks that the HUD value matches it, exceeds
0.3 m/s during the fixture and produces a nonempty gauge. This supplements the
body-less audio-separation fixture. Remaining manual checks above still apply,
including actual seat swaps/death/disconnect/dimension transitions and a visual
inspection of long selected-item names at the user's chosen GUI scales.

## Disposable rendered-frame acceptance

The opt-in client harness also observes the real GUI event dispatch and captures
framebuffer PNGs for survival and creative at requested scales 1–4, plus exit and
re-entry. Every captured frame requires all four actual gauge layer events with
the expected cancellation/restoration state. It uses a long selected-item name;
`client-acceptance-hud-frames.json` records actual scaled viewport dimensions, since
Minecraft can clamp a requested scale to fit the window. CI preserves ten PNGs
and the manifest in the client acceptance artifact. The harness restores its
local test mode, inventory and GUI settings after the mode/scale sequence.
Screenshot capture and layer assertions alone do not establish visual approval.
