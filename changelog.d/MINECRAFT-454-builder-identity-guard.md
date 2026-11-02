bump: patch

### Fixed
- A resident that requested a home while unemployed, then later took a job as a temporary Builder, could end up assigned to build its own home. Dynamic Towns now refuses to bind or advance that request while the same resident is the active Builder, leaving the request intact for a different Builder to complete instead.
