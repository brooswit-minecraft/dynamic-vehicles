bump: patch

### Fixed
- **Town-road progress after construction handoff (MINECRAFT-453).** Road lifecycle regression coverage now proves that a sole Builder can complete and release its campfire, then funded road work resumes safely across saved progress without adopting a foreign dirt path or charging persisted cells twice.
