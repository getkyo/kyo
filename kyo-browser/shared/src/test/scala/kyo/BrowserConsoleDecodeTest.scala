package kyo

import kyo.internal.CdpEvent
import kyo.internal.ConsoleApiCalledWire

/** Pure decoder tests for the console reshape: the CDP `Runtime.consoleAPICalled` type map (`decodeConsoleApiCalled`), the drain-path level
  * map (`decodeConsoleMessage`), and the wire-level `Browser.parseConsoleEvent` Absent paths.
  *
  * These need no Chrome: they construct the wire records directly and call the `private[kyo]` decoders. The CDP `'warning'` spelling and the
  * drain `'warn'` spelling live in separate decoders and never collide.
  */
class BrowserConsoleDecodeTest extends BrowserTest:

    // ---- decodeConsoleApiCalled: non-structural type map ----

    "decodeConsoleApiCalled maps each non-structural type to the right level" in {
        val cases = Chunk(
            ("log", Browser.ConsoleLevel.Log),
            ("info", Browser.ConsoleLevel.Info),
            ("warning", Browser.ConsoleLevel.Warn),
            ("error", Browser.ConsoleLevel.Error),
            ("debug", Browser.ConsoleLevel.Debug),
            ("trace", Browser.ConsoleLevel.Debug),
            ("dir", Browser.ConsoleLevel.Log),
            ("dirxml", Browser.ConsoleLevel.Log),
            ("table", Browser.ConsoleLevel.Log),
            ("assert", Browser.ConsoleLevel.Error)
        )
        Kyo.foreach(cases) { case (cdpType, expected) =>
            Abort.run[BrowserReadException](Browser.decodeConsoleApiCalled(ConsoleApiCalledWire(cdpType), 0L)).map { result =>
                result match
                    case Result.Success(msg) =>
                        assert(msg.level == expected, s"type '$cdpType' expected level $expected but got ${msg.level}")
                    case other =>
                        fail(s"type '$cdpType' expected Success but got $other")
            }
        }.andThen(succeed)
    }

    // ---- decodeConsoleApiCalled: structural types abort ----

    "decodeConsoleApiCalled aborts on each of the 8 structural types" in {
        val structural =
            Chunk("count", "countReset", "timeEnd", "startGroup", "startGroupCollapsed", "endGroup", "clear", "profile", "profileEnd")
        Kyo.foreach(structural) { cdpType =>
            Abort.run[BrowserReadException](Browser.decodeConsoleApiCalled(ConsoleApiCalledWire(cdpType), 0L)).map { result =>
                result match
                    case Result.Failure(ex) =>
                        assert(
                            ex.getMessage.contains(cdpType),
                            s"structural type '$cdpType' aborted but the message did not mention it: ${ex.getMessage}"
                        )
                        assert(
                            ex.getMessage.contains("unmapped structural console type"),
                            s"structural type '$cdpType' aborted with an unexpected message: ${ex.getMessage}"
                        )
                    case other =>
                        fail(s"structural type '$cdpType' expected Failure but got $other")
            }
        }.andThen(succeed)
    }

    // ---- decodeConsoleMessage (drain): warn vs warning ----

    "decodeConsoleMessage (drain) maps 'warn' not 'warning'" in {
        Abort.run[BrowserReadException](Browser.decodeConsoleMessage(ConsoleMessageWire("warn", "w", 0L), 0L)).map { warnResult =>
            warnResult match
                case Result.Success(msg) =>
                    assert(msg.level == Browser.ConsoleLevel.Warn, s"'warn' expected Warn but got ${msg.level}")
                case other =>
                    fail(s"'warn' expected Success but got $other")
            end match
            Abort.run[BrowserReadException](Browser.decodeConsoleMessage(ConsoleMessageWire("warning", "w", 0L), 0L)).map {
                case Result.Failure(ex) =>
                    assert(
                        ex.getMessage.contains("warning"),
                        s"drain 'warning' aborted but the message did not mention it: ${ex.getMessage}"
                    )
                case other =>
                    fail(s"drain 'warning' expected Failure (drain does not know 'warning') but got $other")
            }
        }
    }

    // ---- decodeConsoleMessage (drain): new info/debug levels ----

    "decodeConsoleMessage maps the new info/debug levels" in {
        Abort.run[BrowserReadException](Browser.decodeConsoleMessage(ConsoleMessageWire("info", "i", 0L), 0L)).map { infoResult =>
            infoResult match
                case Result.Success(msg) =>
                    assert(
                        msg.level == Browser.ConsoleLevel.Info && msg.text == "i",
                        s"expected (Info, 'i') but got (${msg.level}, '${msg.text}')"
                    )
                case other =>
                    fail(s"'info' expected Success but got $other")
            end match
            Abort.run[BrowserReadException](Browser.decodeConsoleMessage(ConsoleMessageWire("debug", "d", 0L), 0L)).map {
                case Result.Success(msg) =>
                    assert(
                        msg.level == Browser.ConsoleLevel.Debug && msg.text == "d",
                        s"expected (Debug, 'd') but got (${msg.level}, '${msg.text}')"
                    )
                case other =>
                    fail(s"'debug' expected Success but got $other")
            }
        }
    }

    // ---- decodeConsoleMessage (drain): offsetMs relative to t0 ----

    "decodeConsoleMessage computes offsetMs relative to t0" in {
        Abort.run[BrowserReadException](Browser.decodeConsoleMessage(ConsoleMessageWire("log", "x", 5000L), 4000L)).map {
            case Result.Success(msg) =>
                assert(msg.offsetMs == 1000L, s"expected offsetMs 1000 but got ${msg.offsetMs}")
                assert(msg.location == Absent, s"expected location Absent but got ${msg.location}")
                assert(msg.text == "x", s"expected text 'x' but got '${msg.text}'")
            case other =>
                fail(s"expected Success but got $other")
        }
    }

    // ---- N2: Browser.parseConsoleEvent Absent path (mirrors the screencast decoder test) ----

    "parseConsoleEvent returns Absent on a non-console params type" in {
        // The dispatcher now carries the decoded typed Wire; a params value that is neither a
        // ConsoleApiCalledWire nor an ExceptionThrownWire projects to Absent.
        val nonConsole = CdpEvent.Generic(method = "Page.loadEventFired", params = ConsoleMessageWire("log", "x", 0L), sessionId = Absent)
        assert(Browser.parseConsoleEvent(nonConsole).isEmpty, "non-console params type should decode to Absent")
    }

end BrowserConsoleDecodeTest
