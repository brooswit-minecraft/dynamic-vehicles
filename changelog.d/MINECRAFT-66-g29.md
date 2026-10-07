bump: minor

### Fixed
- Steering wheel: the Logitech G29 now works with no config edits. It is recognised by name and uses its measured layout (accelerator axis 1, brake axis 2, pedals rest at +1 and press to -1). The axis options are now `steerAxisOverride`, `throttleAxisOverride` and `brakeAxisOverride` (-1 = use the device's profile; others keep 0, 2, 3), replacing `steerAxis`, `throttleAxis` and `brakeAxis`, so an old config file no longer pins the wrong axes.
