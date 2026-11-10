bump: minor

### Changed
- **Dispatcher moved to its own mod.** The Dispatcher villager and delivery-contract gameplay (Dispatch Board, offers, contracts, ambushes, HUD/waypoint) now live in the new standalone [Courier Craft](https://github.com/brooswit-minecraft/courier-craft) mod instead of here. Dynamic Vehicles no longer registers the Dispatcher's blocks, block entity, POI type, villager profession or menu; Builder and Pathfinder, and the shared `village`/`hud` helpers they still use, are unaffected. Existing saves keep resolving: Courier Craft registers the moved objects under the same `dynamicvehicles` registry namespace they always had. Requires Courier Craft (coinstalled) for players who want the Dispatcher back; see its README for the upgrade pairing.
