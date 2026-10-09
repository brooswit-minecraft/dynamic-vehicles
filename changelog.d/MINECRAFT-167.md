bump: minor

### Added
- Steering wheel: the shifter paddles now drive the horn and handbrake instead of the jump key while a wheel is active (right paddle = handbrake, left paddle = honk). New config options `wheel.handbrakeButton` and `wheel.honkButton` (GLFW button index, -1 to disable); defaults are an unverified guess for G29-class wheels, confirm with `/dvwheel`.

### Fixed
- Keyboard-only and gamepad-only play (no wheel detected) is unchanged: jump still drives the horn and handbrake exactly as before.
