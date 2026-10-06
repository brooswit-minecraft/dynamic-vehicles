bump: minor

### Added
- **Sable rigid-body car, suspension (E8 S2).** With `useSablePhysics = true` (default off) and the Sable mod installed, the car is a Sable box rigid body held up by four raycast spring-dampers (per-wheel force applied at the contact point). It settles at its ride height and rides over partial layered blocks. Driving forces come in the next slice; the simple car physics stays the default until then.
- Sable is an optional dependency (`[2.0.5,)`), compiled against only. Its PolyForm Shield 1.0.0 license is noted in the README.
