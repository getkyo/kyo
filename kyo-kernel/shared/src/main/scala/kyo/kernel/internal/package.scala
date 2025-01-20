package kyo.kernel.internal

import kyo.kernel.ArrowEffect

private[kernel] inline def maxStackDepth  = 512
private[kernel] inline def maxTraceFrames = 16

private[kernel] type IX[_]
private[kernel] type OX[_]
private[kernel] type EX <: ArrowEffect[IX, OX]
