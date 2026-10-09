bump: patch

### Fixed
- The per-wheel steer angle published to clients (MINECRAFT-122) now tracks `steer`/`forwardSpeed` for every wheel every tick, including an airborne/zero-load/raycast-miss wheel: it is computed and stored above the force guard instead of after it, where it previously froze at its last grounded value. The angle driving the actual tire physics (`forward.rotateAxis(...)`) is unchanged - the published value is a pure side-channel read from the same computation. `suspensionTravel` is also hardened: the field is now `final` and populated by copying values in (`System.arraycopy`) rather than by reassigning it to the tick-local `compressions` array, so a future write to that local can no longer alias and mutate already-published state.
