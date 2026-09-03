package kyo.kernel.internal

import kyo.Frame
import kyo.kernel.Arrow

// The stack-safety suspension threshold is platform-specific (smaller call stacks on Native
// and WASM need to suspend sooner); each platform sets it in kyo.internal.Platform.
// Diverges from main: main reads kyo.internal.Platform.maxStackDepth; the evaluator's rescue
// budget is a fixed 512 until the platform sweep measures the per-platform values.
private[kernel] inline def maxStackDepth = 512

// Diverges from main: 16 on main, 64 here (EffectTrace's frame budget).
private[kernel] inline def maxTraceFrames = 64

// Diverges from main: the erased aliases IX, OX and EX live in Eval with VX and CX.

// Not on main: rendering helpers for the node and arrow toStrings.
private[kernel] def short(v: Any): String =
    v match
        case v: Pending[?, ?]            => v.toString
        case v: Arrow.Chain[?, ?, ?, ?]  => v.toString
        case _: Arrow.Id[?]              => "Id"
        case _: Arrow.Transform[?, ?, ?] => "Transform"
        case v                           => v.toString

private[kernel] def site(frame: Frame): String =
    val callee = frame.calleeName
    if callee.isEmpty then s"${frame.callerName}(${frame.position.show})"
    else s"${frame.callerName}.$callee(${frame.position.show})"
end site
