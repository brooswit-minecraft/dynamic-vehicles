bump: patch

### Fixed
- A resident that requested its own home (and/or workplace) capacity, then separately self-built and claimed its own matching construction site as a temporary Builder, could never have that site actually complete - the bind was permanently refused, leaving the campfire and the resident's own admission stuck forever. This self-claim identity (beneficiary == builder == live claimant) is now a genuine, bound construction mode: the site builds through the ordinary claimant-only path (receiving everything the site itself offers, not only what was originally requested) and the admission completes once the resident actually, verifiably receives its own home/profession.
- This mode never bypasses the existing active-Builder-containment or distinct-beneficiary self-binding guards, which continue to apply unchanged whenever the beneficiary and the claimant are genuinely different villagers.
