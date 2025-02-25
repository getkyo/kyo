package kyo.test

sealed private[test] trait ConsoleIO
private[test] object ConsoleIO:
    case class Input(line: String)  extends ConsoleIO
    case class Output(line: String) extends ConsoleIO
