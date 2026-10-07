bump: minor

### Changed
- Steering wheel: steering is less sensitive to turn. New `[wheel]` options `lockDegrees` (how far the wheel turns lock to lock, default 900) and `effectiveDegrees` (travel that gives full lock, default 360, i.e. about a 2.5x gain) set the effective range, and `steerCurve` (default 1.25, 1 = linear) makes the response gentler near the centre. Set `effectiveDegrees` equal to `lockDegrees` and `steerCurve` to 1 for the old behaviour.
