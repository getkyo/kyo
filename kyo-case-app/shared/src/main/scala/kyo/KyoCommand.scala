package kyo

import caseapp.core.app.Command
import caseapp.core.help.Help
import caseapp.core.parser.Parser

/** A case-app [[Command]] entrypoint that runs Kyo effects via [[run]] blocks.
  *
  * Parsed options and remaining arguments are available as [[options]] and [[remainingArgs]] inside each [[run]] block, after case-app
  * parsing completes.
  *
  * Note: This class and its methods are unsafe and should only be used as the entrypoint of an application.
  */
abstract class KyoCommand[T](using parser: Parser[T], help: Help[T])
    extends Command[T](using parser, help)
    with KyoCaseAppSupport[T, Async & Scope & Abort[Throwable]]
    with KyoCaseAppInterrupts
    with KyoCaseAppPlatformSpecific
