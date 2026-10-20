bump: minor

### Added
- **Engine inertia and clutch coupling.** The engine now carries its own rotational inertia instead of its RPM snapping to whatever the driven wheels imply: a torque-limited clutch couples it to the drivetrain, locking (engaged) when within its torque capacity and slipping (partial coupling) when demand exceeds it, with neutral staying fully disengaged as before.
- **Engine-axis reaction torque.** The engine's own rotational acceleration now reacts against the chassis (roll, about the car's forward axis) independently of gear, wheel contact, or ground — selecting reverse does not flip this reaction's axis.

### Changed
- **Driven wheels keep turning in the air.** A wheel off the ground now keeps exchanging torque with the engine through the clutch, and the brake slows it, instead of holding the spin it had when it left the ground. The clutch reads no tire load from wheels that are off the ground.
