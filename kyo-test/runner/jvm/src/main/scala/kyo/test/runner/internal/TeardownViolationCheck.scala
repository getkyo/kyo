package kyo.test.runner.internal

/** Thrown from the forked runner's `done()` when a component reported an authoritative teardown violation via
  * [[kyo.internal.Diagnostics.reportViolation]] (see [[SbtRunner.runEndOfRunChecks]]).
  *
  * Unlike [[StrandedOpCheck]] (a live loop's lost wakeup, inferred from two probe samples a settle window apart), a violation here is a
  * component's own definitive, post-mortem finding, e.g. a poller whose terminal exit re-swept its close obligations and found one still
  * outstanding after every legitimate submission window had already passed. [[StrandedOpCheck]]'s classifier exempts a closed component
  * by design (it is scoped to detecting a stuck live loop), so a closed-but-leaked-obligation regression needs this separate, always-on
  * check to surface as a hard test failure instead of silently passing.
  */
private[runner] object TeardownViolationCheck:
    final class Detected(violations: List[String])
        extends RuntimeException(
            "kyo-test teardown violation check failed:\n  - " + violations.mkString("\n  - ") +
                "\n\nA component reported a definitively detected stranded obligation during its own teardown (not inferred from a " +
                "probe sample): this is the closed-component leaked-obligation class the stranded-op probe check does not catch."
        )
end TeardownViolationCheck
