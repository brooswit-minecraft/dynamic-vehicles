# Village Delivery Dispatcher — tuning knobs and dev-client verification

Covers the delivery feature (`...dynamicvehicles.delivery`, epic MINECRAFT-76).
Written by MINECRAFT-131, the no-playtest config-and-docs carve-out: it makes
every tuning value a documented, named server config entry, and lists what
nobody has yet observed in a running game. **No values here were retuned.**
Actual tuning is gated on a person playing the loop — see "Values most
needing play feedback" below.

All entries live in `DispatcherOfferConfig` (root package), written to
`dynamicvehicles-dispatcher-server.toml` on the server. Every entry below
already existed in config before this ticket **except** `rewardPayoutFloor`,
`dispatcherAcquisitionCapPerVillage`, and `dangerRollMode`, which this ticket
added (moving a hard-coded value, or exposing an inherited design question,
per the sections below). Moving a value into config never changed its
numeric default — behaviour today is identical to behaviour before this PR.

## Destination rings and offer slots

| Config key | Unit | Default | Range | What to watch |
|---|---|---|---|---|
| `noviceMaxRing` | ring (one ring = one village-placement-region step out) | 2 | 1–1000 | How far a brand-new Dispatcher can reach. Low values make early offers predictable. |
| `apprenticeMaxRing` | ring | 4 | 1–1000 | — |
| `journeymanMaxRing` | ring | 6 | 1–1000 | — |
| `expertMaxRing` | ring | 10 | 1–1000 | — |
| `masterMaxRing` | ring | 16 | 1–1000 | Should stay the largest reach; a Master offer that never feels farther than a Journeyman's is a sign these five values collapsed together. |
| `noviceSlots` | offer slots | 1 | 1–50 | How many simultaneous destination offers a Dispatcher at this level shows. |
| `apprenticeSlots` | offer slots | 2 | 1–50 | — |
| `journeymanSlots` | offer slots | 3 | 1–50 | — |
| `expertSlots` | offer slots | 4 | 1–50 | — |
| `masterSlots` | offer slots | 5 | 1–50 | More slots at higher levels is the intended curve; watch for a screen so full it stops being a readable choice (AC1's trade-list-like UI assumes a short list). |

## Danger and reward

| Config key | Unit | Default | Range | What to watch |
|---|---|---|---|---|
| `dangerMin` | 0..1 scale | 0.0 | 0.0–1.0 | Lower bound of a contract's rolled danger. |
| `dangerMax` | 0..1 scale (exclusive) | 1.0 | 0.0–1.0, must be `> dangerMin` | Upper bound. |
| `rewardPerBlock` | reward units / block | 0.2 | 0.0–1000.0 | Scales the whole reward curve. **Lowering this widens the flat region created by `rewardPayoutFloor` below** — see that section. |
| `minRewardDangerMultiplier` | multiplier | 1.0 | 0.0–100.0 | Reward multiplier at `dangerMin`. |
| `maxRewardDangerMultiplier` | multiplier | 3.0 | 0.0–100.0, must be `>= minRewardDangerMultiplier` | Reward multiplier at `dangerMax`. A high-danger, short trip can still out-pay a long, safe one through this multiplier — that's deliberate (AC4). |
| `rewardPayoutFloor` | emeralds | 1 | 0–2147483647 | **New in this ticket** (moved out of `ContractTickHandler`, which hard-coded `max(1, round(reward))`). See "Reward payout floor" below. |
| `dangerRollMode` | enum: `PER_REGION` (only implemented) / `PER_OFFER` (placeholder) | `PER_REGION` | — | **New in this ticket**, documentation-only. See "Danger determinism" below — selecting `PER_OFFER` currently has no effect. |
| `dispatcherAcquisitionCapPerVillage` | villagers, or `-1` for uncapped | -1 | -1 or 0–2147483647 | **New in this ticket**, documentation-only — **not enforced by any mechanism yet**. See "Dispatcher acquisition / density" below. |

Reward currency (emeralds, `Items.EMERALD` in `ContractTickHandler`) is
**not configurable**. It was never specified by any slice's acceptance
criteria; slice (e) (MINECRAFT-110) chose emeralds as the natural
villager-economy currency and disclosed that choice as its own call. Making
the currency itself configurable would need a registry-backed config value
(an item ID with validation and a safe fallback) rather than a plain
`ModConfigSpec` primitive, which is not a trivially behaviour-preserving
change — so it stays hard-coded pending a person deciding delivery should
pay something else.

### Reward payout floor

`ContractTickHandler` pays `max(rewardPayoutFloor, round(reward))` emeralds
on contract completion. The floor is sensible on its own — a completed
delivery paying zero would read as broken — but it interacts multiplicatively
with `rewardPerBlock` and the danger multiplier: **every computed reward
below the floor pays identically**, flattening the bottom of the reward
curve that `reward = distance × rewardPerBlock × dangerMultiplier` otherwise
keeps strictly monotonic.

With today's defaults (`rewardPerBlock` 0.2, danger multiplier 1.0–3.0), the
floor binds below a computed reward of 1.0, i.e. `distance × 0.2 ×
dangerMultiplier < 1.0`. At minimum danger (multiplier 1.0) that's
`distance < 5` blocks; at maximum danger (multiplier 3.0) it's `distance <
1.67` blocks. In practice almost nothing falls under it today, since
real destination distances are far larger than 5 blocks.

**This is a trap for future tuning specifically:** if a person asks to lower
`rewardPerBlock` to make long trips feel less lucrative, the floor silently
swallows an ever-larger share of the curve from the bottom up, and short
contracts stop paying differently from each other long before anyone
connects the cause. UNVALIDATED — nobody has played the loop to see whether
a short delivery feels worth doing at any rewardPerBlock value.

### Danger determinism (`dangerRollMode`)

`OfferGenerator.rollDanger(seed, region, config)` deliberately takes no
distance or ring parameter — danger is a pure function of `(world seed,
destination region)`. **The same village is always equally dangerous,
forever, in a given world.** Re-opening a Dispatcher, or a different
Dispatcher offering the same destination, yields identical danger. This was
a deliberate, approved design decision in slice (c) (MINECRAFT-107, PR #40),
not a defect, and it is structural: keeping `rollDanger` parameter-free is
what makes danger's independence from distance unbreakable rather than
merely true today.

Trade-off, both sides, UNVALIDATED:
- **For:** the player accumulates durable, learnable world knowledge ("the
  run past the ravine is always the dangerous one"); nobody can re-open a
  Dispatcher repeatedly hunting a cheap, safe roll.
- **Against:** the spec's phrasing ("generated per contract") more naturally
  suggests per-offer variation, and a world where risk never varies for a
  given route may feel static once learned.

`dangerRollMode` exists to name this choice, not to let it be flipped
casually: only `PER_REGION` is implemented. `PER_OFFER` is a documented
placeholder — selecting it today has **no effect**; danger still rolls
`PER_REGION`. Do not implement `PER_OFFER` without play feedback saying the
loop feels static; re-rolling per offer introduces reroll-shopping (keep
re-opening until a safe roll appears), which is a real cost traded for
another.

### Dispatcher acquisition / density

Slice (b) (MINECRAFT-105, PR #36) bound the Dispatcher profession to the
vanilla Bell by having its `heldJobSite`/`acquirableJobSite` predicates
recognize the existing `PoiTypes.MEETING` type (`DispatcherProfession`) —
the only non-invasive option, since a BlockState maps to exactly one
PoiType and the Bell's states are already claimed by `MEETING`. Consequences,
disclosed by that slice and still true today:

- An employed Dispatcher occupies one of the Bell's 32 `MEETING` POI
  tickets for as long as it holds the job.
- **Any unemployed villager at any bell can take the Dispatcher
  profession**, competing with real workstations (farmer, librarian,
  toolsmith, …) for the village's villager population. There is no cap, no
  rarity weighting, and no per-village limit.

`dispatcherAcquisitionCapPerVillage` exists to name this knob so a future
tuning pass can find it, **not because any enforcement exists**: its
default (`-1`, uncapped) is today's only implemented behaviour, and no code
reads this value to actually cap acquisition. Building real enforcement
would need new machinery — tracking how many Dispatchers a village currently
has and vetoing further profession changes past some count, which the
vanilla villager-profession-change path does not support for free — and
doing that blind, with no feedback on whether a cap is even wanted or what
number it should be, would repeat this ticket's own invented-constant
mistake. UNVALIDATED — nobody has observed what sustained Dispatcher
acquisition does to a real village's farmer/librarian/toolsmith population
over time. The plausible failure mode is a village quietly losing those
professions to Dispatchers, since the Bell is the most universally present
job site in the game.

## Pillager ambush (danger made concrete during travel)

| Config key | Unit | Default | Range | What to watch |
|---|---|---|---|---|
| `ambushRollIntervalTicks` | game ticks (20/sec) | 600 (30s) | 20–2147483647 | How often an active contract gets one ambush roll attempt. |
| `ambushMinRollChance` | probability | 0.05 | 0.0–1.0 | Roll success chance at `dangerMin`. |
| `ambushMaxRollChance` | probability | 0.6 | 0.0–1.0, must be `>= ambushMinRollChance` | Roll success chance at `dangerMax`. |
| `ambushMinEncounterSize` | pillagers | 1 | 1–50 | Pillager count on success at `dangerMin`. |
| `ambushMaxEncounterSize` | pillagers | 4 | 1–50, must be `>= ambushMinEncounterSize` | Pillager count on success at `dangerMax`. Large values plus a short roll interval compound into a crowded encounter — watch both together. |
| `ambushSpawnMinDistance` | blocks | 10.0 | 1.0–1000.0 | Nearest an ambush spawn offset may land from the traveling player/vehicle. |
| `ambushSpawnMaxDistance` | blocks | 20.0 | 1.0–1000.0, must be `> ambushSpawnMinDistance` | Farthest it may land. |
| `ambushSpawnArcDegrees` | degrees | 120.0 | 1.0–360.0 | Angular spread centered on heading — the "ahead matters" cone. A narrow arc makes ambushes feel scripted/predictable; 360° removes the "ahead" read entirely. |
| `ambushMaxEncounterLifetimeTicks` | game ticks | 6000 (5min) | 20–2147483647 | Hard backstop: an ambush mob despawns after this long regardless of other state, so a mob that wandered off or whose owner never reconnects can't accumulate forever. |
| `ambushNearbyPlayerRadius` | blocks | 24.0 | 0.0–1000.0 | See "Nearby-player radius" below. |

### Nearby-player radius is a cleanup-latency knob, not only a fairness knob

`EncounterCleanup.shouldDespawn` is `(stale || ownerGone) && !otherPlayerNearby`
— the nearby-player veto gates **both** branches: the lifetime backstop
*and* the owner-gone (logout/contract-end) case. A player who stays near a
stale or orphaned ambush mob defers its cleanup for as long as they remain
there. This is deliberate and bounded, not a leak — the encounter record is
retained and the predicate re-runs every sweep, so the mob despawns the
moment the veto lifts — but it is the **only knob that trades the epic's
"must not accumulate" criterion against its "don't despawn out from under
another player" criterion** against each other.

Raising `ambushNearbyPlayerRadius` widens the window in which cleanup can be
deferred (more area counts as "someone's nearby"); lowering it narrows that
window but makes it easier for a mob to despawn while a player is still
plausibly engaged with it. UNVALIDATED — nobody has played this trade-off.
Do not remove the veto from the staleness branch to "fix" the latency: that
trades a bounded deferral for a real fairness violation (despawning a mob
out from under someone fighting it), which is the same unmeasured-for-
unmeasured swap this ticket exists to prevent.

## Deadline (time allowance)

| Config key | Unit | Default | Range | What to watch |
|---|---|---|---|---|
| `timeAllowanceBaseTicks` | game ticks (20/sec) | 6000 (5min) | 0–2147483647 | Fixed component of a contract's deadline, granted regardless of distance. |
| `timeAllowanceTicksPerBlock` | game ticks / block | 4.0 | 0.0–1000.0 | Additional deadline per block of approximate distance (at 4.0 ticks/block ≈ 0.2 sec/block, i.e. ~5 blocks/sec of allowance). |

UNVALIDATED — whether the deadline reads as generous or tense is exactly a
felt question; nobody has played a contract against its own clock yet.

## Values most needing real play feedback

Named here per the ticket's own requirement, and in the MINECRAFT-131 ticket
comment: **Dispatcher acquisition/density** (`dispatcherAcquisitionCapPerVillage`),
**danger determinism** (`dangerRollMode`), **the reward floor and its
interaction with `rewardPerBlock`** (`rewardPayoutFloor`), **the
nearby-player radius cleanup/fairness trade-off** (`ambushNearbyPlayerRadius`),
and **deadline allowance** (`timeAllowanceBaseTicks`/`timeAllowanceTicksPerBlock`).

## What a person should check in a dev client, in order

Nine behaviours across slices (b) MINECRAFT-105, (d) MINECRAFT-109, (e)
MINECRAFT-110, and (f) MINECRAFT-112 have never been observed in a running
game — each slice said so plainly in its own PR, but those notes are
scattered across four PR descriptions. Collected here, in the order they'd
naturally come up walking through the loop once:

1. **Dispatcher profession renders** on a villager that has taken the job
   (PR #36, slice b).
2. **A villager actually takes the Dispatcher profession** at a Bell when
   unemployed and the job site is free (PR #36, slice b).
3. **Bells still ring normally** — binding the profession to the existing
   `MEETING` POI type didn't break the Bell's vanilla raid-alert behaviour
   (PR #36, slice b).
4. **Villagers still gather at the Bell** for vanilla meeting/gathering
   behaviour, unaffected by the Dispatcher binding (PR #36, slice b).
5. **The offer screen's appearance**: resembles a vanilla trade list (AC1);
   the danger number and its colour are readable at a glance (AC3); distance
   and danger read as independent of each other to a player looking at the
   screen (AC4) (PR #45, slice d).
6. **The real `RegistryFriendlyByteBuf` round-trip** for the offer packet —
   the unit-tested codec's field order matches what actually goes over the
   wire in a live client/server pair (PR #45, slice d) — and the same for
   `DeliveryHudPayload` (PR #47, slice e).
7. **The HUD and its direction arrow** — appearance and whether the arrow
   actually points toward the destination while traveling (PR #47, slice e).
8. **The emerald payout** in a live world on contract completion — the
   actual item hand-back, not just the computed count (PR #47, slice e).
9. **Whether an ambush is actually drivable-through** — spawn terrain
   sanity (pillagers don't spawn somewhere unreachable or absurd) and real
   cleanup timing end-to-end (PR #49, slice f). No client has been run for
   this slice at all.

Danger category labels (`gauge.dynamicvehicles.danger.*` in `en_us.json`)
are already translated and should stay that way — nothing to check here,
just don't regress them.
