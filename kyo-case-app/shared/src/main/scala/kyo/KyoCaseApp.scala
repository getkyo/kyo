package kyo

import caseapp.CaseApp
import caseapp.core.help.Help
import caseapp.core.parser.Parser

/** A case-app [[CaseApp]] entrypoint that runs Kyo effects via [[run]] blocks.
  *
  * Parsed options and remaining arguments are available as [[options]] and [[remainingArgs]] inside each [[run]] block, after case-app
  * parsing completes.
  *
  * Note: This class and its methods are unsafe and should only be used as the entrypoint of an application.
  */
abstract class KyoCaseApp[T](using parser0: Parser[T], messages: Help[T])
    extends CaseApp[T](using parser0, messages)
    with KyoCaseAppSupport[T, Async & Scope & Abort[Throwable]]
    with KyoCaseAppInterrupts
    with KyoCaseAppPlatformSpecific

object KyoCaseApp
