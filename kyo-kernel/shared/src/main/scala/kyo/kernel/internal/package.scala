package kyo.kernel.internal

import kyo.Frame
import kyo.kernel.Arrow

// The stack-safety suspension threshold is platform-specific (smaller call stacks on Native
// and WASM need to suspend sooner); each platform sets it in kyo.internal.Platform. It is the
// default of the Safepoint.period flag, which is what the rest of the kernel reads.
private[kernel] inline def maxStackDepth = kyo.internal.Platform.maxStackDepth

// Fixed rather than platform-specific: a trace is bounded by how much of it is worth reading.
private[kernel] inline def maxTraceFrames = 64

// No arm for Arrow.Transform: every one is a Pending node or an Arrow.Step, which render with their own site.
private[kernel] def short(v: Any): String =
    v match
        case v: Pending[?, ?]           => v.toString
        case v: Arrow.Chain[?, ?, ?, ?] => v.toString
        case _: Arrow.Id[?]             => "Id"
        case v                          => v.toString
