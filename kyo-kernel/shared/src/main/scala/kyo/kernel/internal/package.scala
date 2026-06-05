package kyo.kernel.internal

import kyo.kernel.ArrowEffect

// The stack-safety suspension threshold is platform-specific (smaller call stacks on Native
// and WASM need to suspend sooner); each platform sets it in kyo.internal.Platform.
private[kernel] inline def maxStackDepth  = kyo.internal.Platform.maxStackDepth
private[kernel] inline def maxTraceFrames = 16

private[kernel] type IX[_]
private[kernel] type OX[_]
private[kernel] type EX <: ArrowEffect[IX, OX]
