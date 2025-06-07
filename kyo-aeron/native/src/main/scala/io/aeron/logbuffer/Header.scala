package io.aeron.logbuffer

import org.agrona.DirectBuffer

class Header private[logbuffer] ():

    def offset(): Int          = 0
    def streamId(): Int        = 0
    def sessionId(): Int       = 0
    def termId(): Int          = 0
    def termOffset(): Int      = 0
    def frameLength(): Int     = 0
    def reservedValue(): Long  = 0L
    def version(): Int         = 0
    def flags(): Int           = 0
    def `type`(): Int          = 0
    def initialTermId(): Int   = 0
    def position(): Long       = 0L
    def buffer(): DirectBuffer = null
end Header
