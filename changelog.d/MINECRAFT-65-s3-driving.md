bump: minor

### Added
- **Sable rigid-body car, tires and driving (E8 S3).** `useSablePhysics` now defaults to **true** (set it to false for the old simple model, or it is used automatically if Sable is missing). Each wheel is each wheel is a friction-circle tire: it cancels sideways sliding, delivers all-wheel drive force, brakes and rolls with resistance, and slides when demand exceeds grip (the handbrake, space, locks the rear and frees its lateral grip so the car can drift). Steering turns the front axle with a speed-sensitive angle. Slip beyond a threshold is reported to Dynamic Terrain (`TireSlip`) for the block under the wheel. Op debug command `/dvdrive <throttle> <steer> <ticks>` drives cars without a rider.
