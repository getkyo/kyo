package kyo.proto.kernel.internal

import kyo.Frame

trait Kyo[+A, -S]:
    def frame: Frame
