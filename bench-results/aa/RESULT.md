# The A/A bracket, run for real

Five legs, same sha on both arms (`d85ee6821f` against itself), two rows, `-f 1`, timing only. Any row
this classifies is false by construction, which is what makes it the only check in the suite that can
fail in the direction that matters.

## First run: a false positive

    🔴 nestedPayloadsUnwrapInMaps  +3.8%  REGRESSED
       flat to within +-2.60% (alpha 0.025, df 3)

Identical sources on both arms. The giveaway was in the harness's own table: each leg reported
±0.32 on 6.12, about **5.2% of itself**, against a computed threshold of **2.60%**. A threshold below
the uncertainty of the legs it is built from classifies that uncertainty as a result.

The retired drift estimator carried exactly this floor, `max(spread, ownError)`, and the replicate
statistic lost it when it replaced it. Thirty-one unit tests, a simulation-backed review, and three
rounds of plan review did not find a missing `max`. One real bracket did.

## Second run: clean

    A/A null: clean, no control row classified against another control leg.
    ⚪ continuationBodiesFuse      +0.7%
    ⚪ nestedPayloadsUnwrapInMaps  +0.9%
    Every flat row is flat to within its own resolution, at worst +-5.77%

## The consequence, which is not comfortable

The resolution widened from 2.60% to **5.77%**, and that is the honest figure for this configuration:
five legs at `-f 1`. It follows that **this configuration cannot resolve the +4.55% regression this
campaign spent the night diagnosing.** The bracket would call it flat.

That is not an argument against the floor. It is the floor telling the truth about what one fork per
leg can see, where before it would have reported a verdict it had no standing to give. The response
is more forks per leg, or more legs, both of which cost time and neither of which is a threshold
adjustment.

Recorded rather than smoothed over, because the tempting move here is to relax the floor until the
known result becomes visible again, and that is precisely the reward-hack this harness exists to
prevent, aimed at its own gate.

## Calibration: more forks does not help, more legs would

Same A/A at three forks per leg instead of one:

    -f 1   leg error +-0.30 (4.9%)    resolution +-5.77%
    -f 3   leg error +-0.05 (0.8%)    resolution +-5.72%

Tripling the forks cut each leg's own error six-fold and moved the resolution by 0.05 points. The
variance is **between** legs, not within them: each leg measures itself precisely and the legs
disagree with each other. Leg one of the control read 6.17 and leg one of the variant 6.39, a 3.6%
gap between identical sources, while the five-leg means differed by 0.0%.

So the earlier writeup was half wrong, and measuring it is what showed that. "More forks or more
legs" is not a choice here: forks buy precision within a leg, which is not the quantity that limits
this bracket. Only more legs, or a quieter machine, moves the resolution.

That also means the +4.55% regression stays below this bracket's resolution at any fork count worth
paying for, and the way to see it is more legs.

## A reporting defect the calibration exposed

The table printed `6.17 -> 6.39` beside `+0.0%`. Those were leg one's scores next to a mean-based
percentage, so a reader doing the arithmetic on the displayed numbers got +3.6% while the harness
reported +0.0%, and both were "right" about different quantities. The delta now carries the replicate
means and the pooled spread, so the displayed numbers are the ones the displayed percentage comes
from, and a test asserts exactly that.
