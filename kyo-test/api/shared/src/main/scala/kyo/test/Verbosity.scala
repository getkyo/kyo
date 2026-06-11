package kyo.test

/** Controls how much output the console reporter emits.
  *
  * Pass a `Verbosity` to `Reporters.console(verbosity)` or `ConsoleReporter(verbosity)`.
  *
  * @see
  *   `kyo.test.runner.ConsoleReporter` which reads Verbosity to decide per-leaf output detail
  * @see
  *   [[kyo.test.RunConfig]] where Verbosity is stored in the verbosity field
  * @see
  *   `kyo.test.runner.Reporters.console` factory that accepts a Verbosity value
  */
enum Verbosity derives CanEqual:
    /** Suppress per-leaf pass lines; only failures, cancellations, and the final summary are printed. */
    case Quiet

    /** Print one line per leaf (pass or fail) plus suite and run summaries. This is the default. */
    case Normal

    /** Print per-leaf start and complete lines, full diagrams, and all summaries. */
    case Verbose
end Verbosity
