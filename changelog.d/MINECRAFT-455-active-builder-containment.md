bump: patch

### Fixed
- A town's construction handoff could, in a narrow timing window, bind or complete a bed/workstation handoff to a beneficiary who was simultaneously an active Builder claimant elsewhere. Binding and closing now both refuse a beneficiary with an active Builder claim, leaving the admission for a genuinely eligible beneficiary instead.
- Reserving a beneficiary's bed or workstation could re-acquire (and later release) a POI ticket the beneficiary already, legitimately held from an earlier reservation, rather than recognizing and reusing it. A resident's own verified, currently-held ticket is now reused; only a ticket genuinely newly acquired this attempt is released if the closing placement is later interrupted.
