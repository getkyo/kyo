package kyo.proto.kernel.internal

import kyo.Frame

trait Node[+A, -S]:
    def frame: Frame
