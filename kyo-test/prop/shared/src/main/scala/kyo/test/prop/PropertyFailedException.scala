package kyo.test.prop

import kyo.Frame

/** Thrown by forAll when a property fails after shrinking.
  *
  * Carries both the original failing sample (before shrinking) and the minimal shrunken counterexample, plus the underlying assertion
  * failure. The message is formatted so that ConsoleReporter displays it naturally via the AssertionFailed diagram path.
  *
  * @param originalSample
  *   the first sample that caused the property to fail
  * @param shrunkValue
  *   the minimal counterexample found by the shrink loop
  * @param cause
  *   the assertion failure (or other throwable) from the property body
  * @param seed
  *   the random seed used for this run (for reproduction); copy it into `forAllSeeded(seed, gen) { ... }` to replay
  *   deterministically
  * @param frame
  *   the source location of the forAll call
  * @see
  *   [[kyo.test.prop.PropertyTest]] the forAll DSL method that throws this exception
  * @see
  *   [[kyo.test.prop.Gen]] the generator whose shrink method produced the shrunken counterexample
  * @see
  *   [[kyo.test.prop.Shrink]] the shrink algorithms used by Gen instances
  * @see
  *   [[kyo.test.TestResult]] the leaf outcome enum; catching this exception produces the `Failed` case
  */
final class PropertyFailedException(
    val originalSample: Any,
    val shrunkValue: Any,
    cause: Throwable,
    val seed: Long
)(using Frame)
    extends kyo.KyoException(
        s"Property failed!\n" +
            s"  Seed:     $seed\n" +
            s"  Original: $originalSample\n" +
            s"  Shrunk:   $shrunkValue\n" +
            s"  Cause:    ${cause.getMessage}",
        cause
    )
end PropertyFailedException
