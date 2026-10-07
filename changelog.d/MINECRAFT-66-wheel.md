bump: minor

### Added
- Steering wheel and pedals (MINECRAFT-66, M1): a joystick-class wheel (Logitech G29, G920, G923 and similar) steers and drives the cars through GLFW, with the keyboard still working alongside it. Client config section `wheel` sets the device, axis numbers, inversion, combined pedals, deadzones and pedal rest; `/dvwheel` shows the live axes. The startup log lists every joystick GLFW sees.
