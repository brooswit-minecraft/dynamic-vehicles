bump: patch

### Fixed
- Fixed a physical H-pattern shifter's neutral release leaving the previously engaged gear stuck
  selected: a genuinely released, fully-covered H-pattern sample now reports NEUTRAL instead of no
  decision, while an unconfigured shifter or a short/invalid sample still reports no decision rather than
  a false neutral. Added a `wheel.physicalShifter.mode` option (`H_PATTERN`/`SEQUENTIAL`) so a momentary
  sequential shifter's release keeps retaining the selected gear instead of going to neutral.
- Fixed the accelerator always driving forward regardless of the selected gear, and never blocking
  propulsion in NEUTRAL: it now propels only in the gear's own direction (forward for a forward gear,
  backward for REVERSE), with no propulsion in NEUTRAL, each gated so shifting at speed coasts to a stop
  first instead of instantaneously reversing the car's velocity.
- Fixed the brake pedal occasionally driving the car backward instead of braking (while reversing), and
  releasing the car instead of holding it at low speed; it now always just decelerates the car's actual
  motion, any gear, any speed.
