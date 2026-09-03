package kyo.kernel.internal

import kyo.Frame

trait Kyo[+A, -S]:
    def frame: Frame
