# MINECRAFT-455 resident capacity consumer integration

This continuation recovers the retained four-file consumer/cancellation candidate and adapts it to
PR #202's published binding API at `b555621069006c136babe84b011c52ca9f04af84`.
It depends on that source branch; publication does not approve, merge, or release PR #202.

Allocation first assigns existing fixtures, then requests only the beneficiary's remaining unmet
capacity. Resolved need cancels only that beneficiary's AWAITING_CLAIM admission. Cancellation
preserves its requested profile and refuses BUILDING/COMPLETED records, including their binding
and generation identity. The tests cover persistence, repeated/absent cancellation, exact
beneficiary/region/dimension scope, bound-state preservation, allocation and reuse after reload.

## Clutch fixture correction

The old test changed the same car from one to four substeps after 50 ticks in a randomly placed
structure, with one stone block beneath its centre and a nearby wall. Its full-suite failure was
zero clutch transfer in the late phase, not an invariant violation. One instrumented isolated run
of the original assertions passed: a production clutch defect was not established, and PR #202
does not change this test. A merge is not a causal fix for the failure.

The corrected fixture establishes two grounded cars on separate fixed-coordinate lanes, drives
each through its own full-throttle launch, audits 30 ticks per phase and restores configuration
on every outcome. It retains the reaction identity, capacity bound, positive-transfer assertion
and their original tolerances. Both targeted and integrated runs passed. The integrated run
reported zero reaction gap at both counts and peak clutch torques 162.5984625421948 and
244.00763400691645 N*m respectively. No production drivetrain code changed.

## Reproduction

Use Java 21 and the repository's pinned dependencies. Populate Gradle's dependency cache before
using `--offline`. Choose a fresh disposable directory; do not point this at a player world.

```sh
./gradlew compileJava compileTestJava runGameTestServer --offline --no-daemon \
  -Pm478GameDirectory=/tmp/m455-review-fresh/game > /tmp/m455-review-fresh.log 2>&1
python3 scripts/check_gametest_count.py /tmp/m455-review-fresh.log
```

Validation on 2026-11-02: compile and all **353 required GameTests passed**, process exit 0.
The source inventory guard separately confirmed all 353 selected cases, including the six M455
cases. The retained pre-integration candidate previously passed 943 unit tests in 127 suites;
that suite was not repeated merely to regenerate its already available receipt. This integrated
run compiles test sources but is not a new claim of unit-test execution.

Scope excludes construction durability, PR approval/merge, deployment, and held owner work.

## CI review correction

CI run 41438200872 passed the changed one/four-substep fixture but exposed two separate fixture
preconditions and a missing changelog fragment. The admission case placed its beneficiary at
x=-1584000 while the anchor was x=-1584004, across an exact 384-block town boundary. It now
uses the anchor's town and asserts production entity-iterable membership after two normal ticks.
The production region/ownership checks remain unchanged.

The coast case stopped after only 30 closed-throttle ticks. CI still showed 2832 RPM against
approximately 2060 RPM at the wheel-side shaft, so the engine had not yet spun below the wheels.
The fixture now derives a spin-down budget from the stock limiter, engine inertia and drag,
then retains 15 additional ticks to audit the negative exchange. All signed books, braking,
limiter and slip assertions remain. A two-case focused run passed, exit 0, with 120 actual
throttle/gear/contact/exchange samples retained outside the repository. In that run braking first
occurred at sample 89; the longer coast continued to audit negative exchange through sample 120.
This is targeted local evidence; the changed head still requires CI acceptance.
