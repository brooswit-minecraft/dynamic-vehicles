# MINECRAFT-448 — per-car TC/ABS computer assists

- Added the per-car `computer.tractionControlLevel` and `computer.absLevel` controls (0.0–1.0, both disabled by stock defaults). TC and ABS act on each grounded wheel's existing drive/brake command using its real contact speed and spin; neither selects a gear, adds a force, or acts while contact is absent.
