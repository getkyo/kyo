package kyo.kernel.internal

import kyo.Frame

trait Kyo[+A, -S]:
    /** The stack frame where this suspension was created */
    def frame: Frame
