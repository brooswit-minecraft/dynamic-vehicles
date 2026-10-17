bump: minor

### Added
- Right-clicking the existing delivery dispatch board now opens the same dispatcher offers menu as a level-one Dispatcher villager, through the existing delivery workflow. Each placed board is assigned its own persisted UUID on first use, which together with the current Minecraft day deterministically seeds that board's daily level-one jobs — stable for the same board/day and refreshed through the existing daily workflow, and persisted across save/reload. The board remains a fixed level-one dispatch source: it does not level up, scale with neighboring boards, or add a dispatcher hut, and its existing recipe/assets/job-site role are unchanged.
