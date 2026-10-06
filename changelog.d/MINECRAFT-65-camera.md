bump: minor

### Added
- **Smoother ride and a chase camera.** The client now de-jitters the car's movement and orientation updates: they wait in a short buffer and are used one per tick, so uneven packet arrival no longer shows as stutter in the car, the rider or the camera (about one tick of delay). While riding, the camera yaw eases toward the car's heading (client config `chaseCamera`, default on; `chaseCameraLag` seconds, default 0.25), so you no longer have to keep dragging the view to look forward. Moving the mouse is a temporary free look; after `chaseCameraRecenterSeconds` (default 1.5) without mouse movement the camera eases back. Pitch stays under your control, and it works in third person (F5) too.
