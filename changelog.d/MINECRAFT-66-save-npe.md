bump: patch

### Fixed
- Saving a car when its chunk unloads threw a NullPointerException when Sable had already released the physics body, so the car did not persist. It now saves the last known orientation instead.
