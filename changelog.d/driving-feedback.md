bump: minor

### Fixed
- Tire squeal follows grounded wheelspin and lateral sliding using the tire's
  actual grip budget, without requiring a brake input. Quiet slip updates and
  stopping synchronize reliably; airborne free-wheel spin stays silent.

### Added
- A compact driver-only dashboard displays actual engine RPM and speed in km/h.
  Hide GUI also hides the dashboard. Vehicle forces and terrain wear are unchanged.
  Client and server must update together for the added synchronized telemetry.
