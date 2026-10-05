# Dynamic Vehicles

Early alpha for NeoForge 1.21.1. Requires **Dynamic Terrain**.

## What's in it

- A **4-wheel Car** (creative, Tools & Utilities tab). Right-click a block to place it, right-click the car to get in, drive with the movement keys, sneak for the handbrake.
- A simple physics model with acceleration, braking, reverse and a cornering grip limit.
- **Tire slip wears the world:** wheelspin, locked braking and sliding report to Dynamic Terrain, which decides whether the ground under the wheel changes (needs Dynamic Terrain's erosion enabled). Hard surfaces resist; loose ground wears down.

The look is a placeholder built from vanilla blocks. Handling on different surfaces (grip, roughness, rolling resistance, deformability) comes later.

Source and issues: https://github.com/brooswit-minecraft/dynamic-vehicles
