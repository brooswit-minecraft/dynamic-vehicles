# Boat travel-side lift

The boat applies an additional world-up force at submerged hull points on the side facing its actual horizontal movement. Forward movement lifts the bow, reverse lifts the stern, and sideways movement lifts that side. It uses measured movement, not throttle or camera direction. Water is treated as still; there is no current model.

In the motorboat server config:

| Key | Default | Range | Meaning |
| --- | --- | --- | --- |
| `travelLiftCoefficient` | 8 | 0–500 | Total requested force coefficient, N/(m/s)^2. Zero disables lift. |
| `maxTravelLiftForce` | 1000 | 0–20000 | Total force cap in N across the entire hull. Zero disables lift. |

The force grows with speed squared up to the cap. Positive projection onto travel direction determines the supporting edge. Each point fades from zero to full force over its first 0.25 m of immersion; dry points do not transfer their unused share to other points. Stationary, dry and disabled cases add no lift. Existing buoyancy remains independent. Force is applied as an impulse multiplied by the timestep at the actual hull support point; pitch and roll emerge from physics.

The shared driver-vehicle interface opts cars and boats into steering-wheel movement and the forward chase camera. Aircraft remain outside that opt-in. The existing original worker's unrecovered boat edits have not been recovered or overwritten by this independent implementation.
