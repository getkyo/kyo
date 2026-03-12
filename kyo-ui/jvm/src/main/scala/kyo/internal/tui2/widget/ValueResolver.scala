package kyo.internal.tui2.widget

import kyo.*
import kyo.Maybe.*
import kyo.Signal.SignalRef
import kyo.discard
import kyo.internal.tui2.SignalCollector

/** Shared helpers for resolving union-typed values (static | Signal) used across widgets.
  *
  * Note: `asInstanceOf` is needed here for union type narrowing — Scala 3 erases the union at runtime, so after pattern-matching on
  * `Signal[?]` we must cast to access the concrete type parameter. This is the only place in tui2 that uses casts.
  */
private[tui2] object ValueResolver:

    /** Read the current value of any Signal using its unsafe accessor. */
    private def readSignal[A](s: Signal[A])(using Frame, AllowUnsafe): A =
        s match
            case ref: SignalRef[A @unchecked] => ref.unsafe.get()
            case _                            => Sync.Unsafe.evalOrThrow(s.current)

    /** Resolve `Maybe[String | SignalRef[String]]` to its current String value. */
    def resolveString(
        value: Maybe[String | SignalRef[String]],
        signals: SignalCollector
    )(using Frame, AllowUnsafe): String =
        value match
            case Present(v) =>
                v match
                    case s: String =>
                        s
                    case sr: SignalRef[?] =>
                        signals.add(sr)
                        sr.asInstanceOf[SignalRef[String]].unsafe.get()
            case _ => ""

    /** Set a `Maybe[String | SignalRef[String]]` value (only works for SignalRef). */
    def setString(
        value: Maybe[String | SignalRef[String]],
        newValue: String
    )(using Frame, AllowUnsafe): Unit =
        value match
            case Present(sr: SignalRef[?]) =>
                sr.asInstanceOf[SignalRef[String]].unsafe.set(newValue)
            case _ => ()

    /** Resolve `Maybe[Boolean | Signal[Boolean]]` to its current Boolean value. */
    def resolveBoolean(
        checked: Maybe[Boolean | Signal[Boolean]],
        signals: SignalCollector
    )(using Frame, AllowUnsafe): Boolean =
        checked match
            case Present(v) =>
                v match
                    case b: Boolean =>
                        b
                    case s: Signal[?] =>
                        signals.add(s)
                        readSignal(s.asInstanceOf[Signal[Boolean]])
            case _ => false

    /** Set a `Maybe[Boolean | Signal[Boolean]]` value (only works for SignalRef). */
    def setBoolean(
        value: Maybe[Boolean | Signal[Boolean]],
        newValue: Boolean
    )(using Frame, AllowUnsafe): Unit =
        value match
            case Present(sr: SignalRef[?]) =>
                sr.asInstanceOf[SignalRef[Boolean]].unsafe.set(newValue)
            case _ => ()

    /** Resolve `Maybe[Double | Signal[Double]]` to its current Double value. */
    def resolveDouble(
        value: Maybe[Double | Signal[Double]],
        signals: SignalCollector
    )(using Frame, AllowUnsafe): Double =
        value match
            case Present(v) =>
                v match
                    case d: Double =>
                        d
                    case s: Signal[?] =>
                        signals.add(s)
                        readSignal(s.asInstanceOf[Signal[Double]])
            case _ => 0.0

    /** Set a `Maybe[Double | Signal[Double]]` value (only works for SignalRef). */
    def setDouble(
        value: Maybe[Double | Signal[Double]],
        newValue: Double
    )(using Frame, AllowUnsafe): Unit =
        value match
            case Present(sr: SignalRef[?]) =>
                sr.asInstanceOf[SignalRef[Double]].unsafe.set(newValue)
            case _ => ()

    /** Resolve `Style | Signal[Style]` to its current Style value. */
    def resolveStyle(
        uiStyle: Style | Signal[Style],
        signals: SignalCollector
    )(using Frame, AllowUnsafe): Style =
        uiStyle match
            case s: Style => s
            case sig: Signal[?] =>
                signals.add(sig)
                readSignal(sig.asInstanceOf[Signal[Style]])

    /** Apply a Foreach's render function to an item (union type erasure requires cast). */
    def foreachApply(fe: UI.Foreach[?], idx: Int, item: Any): UI =
        fe.render.asInstanceOf[(Int, Any) => UI](idx, item)

    /** Run a handler action asynchronously (fire-and-forget fiber). */
    def runHandler(action: Unit < Async)(using Frame, AllowUnsafe): Unit =
        discard(Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(action)))

    /** Resolve `Maybe[String | Signal[String]]` — used by Anchor.href, Img.src. Different from resolveString which handles SignalRef
      * (mutable). This handles read-only Signal[String].
      */
    def resolveStringSignal(
        value: Maybe[String | Signal[String]],
        signals: SignalCollector
    )(using Frame, AllowUnsafe): String =
        value match
            case Present(v) =>
                v match
                    case s: String => s
                    case sr: Signal[?] =>
                        signals.add(sr)
                        readSignal(sr.asInstanceOf[Signal[String]])
            case _ => ""

    /** Compute a Foreach item's key string (union type cast centralized here). */
    def foreachKey(fe: UI.Foreach[?], item: Any): String =
        fe.key match
            case Present(f) => f.asInstanceOf[Any => String](item)
            case _          => ""

end ValueResolver
