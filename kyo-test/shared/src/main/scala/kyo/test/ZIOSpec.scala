package kyo.test

import kyo.*

/** Converted from Kyo test's KyoSpec.scala to Kyo.
  */
abstract class KyoSpec[R: Tag] extends KyoSpecAbstract with KyoSpecVersionSpecific[R]:
    self =>
    type Environment = R

    final val Tag: Tag[R] = kyo.Tag[R]

    /** Builds a spec with a single test. */
    def test[In](label: String)(assertion: => In)(implicit
        testConstructor: TestConstructor[Nothing, In],
        frame: Frame,
        trace: Trace
    ): testConstructor.Out =
        // Delegates to Kyo test constructor
        kyo.test.test(label)(assertion)

    def suite[In](label: String)(specs: In*)(implicit
        suiteConstructor: SuiteConstructor[In],
        frame: Frame,
        trace: Trace
    ): Spec[suiteConstructor.OutEnvironment, suiteConstructor.OutError] =
        // Delegates to Kyo suite constructor
        kyo.test.suite(label)(specs*)

    // Runs the spec, handling environment and abort effects
    def runSpec(): Unit < (IO & Async) =
        spec.pipe(
            Abort.run,  // handle abort effects
            Env.run(()) // provide an empty/default environment
        ).eval
end KyoSpec
