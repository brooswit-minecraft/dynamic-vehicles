bump: minor

### Added
- Vehicle radios: cycle Off and every server-configured internet radio station with N (rebindable), or a
  configurable wheel/gamepad button, while driving a car. The server's ordered `radioStations` list and
  each vehicle's current selection/on-off state are synced; every client connects directly to the chosen
  station's URL and plays it itself, vehicle-positioned, stopping the instant the vehicle has no driver.
  Ships one verified default station (OC ReMix). See the README's new "Radio" section.
