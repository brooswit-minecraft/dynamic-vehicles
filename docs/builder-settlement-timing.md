# Builder settlement timing

Builder construction has two independent triggers:

- A completed Builder donation calls the donation settlement path. Its level-scaled roll remains 0%, 2.5%, 5%, 7.5%, or 10% for levels one through five.
- The passive path checks each loaded Builder at most once every 200 server game ticks (about ten seconds). Its level-scaled roll is 0%, 0.02%, 0.04%, 0.06%, or 0.08% per check. At level five, 120 checks over a Minecraft day give roughly a 9.2% chance of at least one successful roll, deliberately comparable to rather than 120 times the former daily 10% rate.

The passive path persists its last check using server game time on the villager. The timestamp is saved before the attempt: a reload retains it, and a forward clock jump produces exactly one eligible check and moves the marker to the new time. No loop catches up skipped intervals. A backward clock change waits until the saved time is again at least one interval behind. The existing noon-only passive villager XP progression remains unchanged.

Both paths use the same preflighted 5x5 placement contract. A successful placement consumes the campfire marker and converts the Builder to unemployed, including a Builder that previously traded; a second path in the same window therefore finds no settlement anchor and cannot perform another destructive placement.
