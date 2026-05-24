package kyo

import caseapp.core.app.Command
import caseapp.core.help.Help
import caseapp.core.parser.Parser

/** A case-app [[Command]] entrypoint that runs Kyo effects via [[run]] blocks.
  *
  * Register effectful work with [[run]] — typically `run { options => ... }` after case-app parsing completes. Use
  * `(options, remainingArgs)` when leftover positionals are required.
  *
  * Note: This class and its methods are unsafe and should only be used as the entrypoint of an application.
  */
abstract class KyoCommand[T](using parser: Parser[T], help: Help[T])
    extends Command[T](using parser, help)
    with kyo.internal.KyoCaseAppSupport[T]
    with kyo.internal.KyoAppRunnerWithInterrupts
    with kyo.internal.KyoAppRunnerPlatform
