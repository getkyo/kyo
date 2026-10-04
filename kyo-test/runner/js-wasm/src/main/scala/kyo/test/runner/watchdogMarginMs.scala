package kyo.test.runner

import kyo.StaticFlag

/** How long, in milliseconds, a leaf may stay past its timeout with the event loop held before the JS and Wasm runner ends the Node
  * process; zero disables the check. Set it with `KYO_TEST_RUNNER_WATCHDOGMARGINMS` or `-Dkyo.test.runner.watchdogMarginMs`.
  *
  * The kill loses the rest of the module's suites, so the margin sits far above any stall a healthy run produces (a garbage-collection pause,
  * a large synchronous decode) and far below the hours a held event loop would otherwise run.
  */
private[runner] object watchdogMarginMs extends StaticFlag[Int](30000)
