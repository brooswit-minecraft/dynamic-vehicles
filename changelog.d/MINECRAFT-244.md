bump: minor

### Added
- Cars gain an explicit drivetrain gear (MINECRAFT-228): `REVERSE`, `NEUTRAL`, and six named
  forward gears (`D1`-`D6`), matching the owner's real 6+R shifter hardware so a later story can
  address any of the six gears directly. Keyboard and gamepad get a new key (default `R`,
  rebindable in Controls) that toggles between `REVERSE` and the default forward gear; a wheel's
  honk/handbrake paddles are untouched.

### Fixed
- Holding brake at a standstill can no longer reverse the car on any input device, in either the
  default physics or the optional Sable physics path. Only the new explicit `REVERSE` gear lets
  the car drive backward.
