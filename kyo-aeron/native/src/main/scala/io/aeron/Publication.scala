package io.aeron

import io.aeron.logbuffer.BufferClaim // Will be stubbed later

class Publication private[aeron] ():
    def isConnected(): Boolean                                = ???
    def tryClaim(length: Int, bufferClaim: BufferClaim): Long = ???
    def close(): Unit                                         = ???
end Publication

object Publication:
    // Constants used in Topic.scala
    val BACK_PRESSURED: Long = -1L // Standard Aeron backpressure code
    val NOT_CONNECTED: Long  = -2L // Standard Aeron not connected code
    val ADMIN_ACTION: Long   = -3L // Standard Aeron admin action code
    val CLOSED: Long         = -4L // Standard Aeron closed code
    // Other codes exist but these are the ones used in the snippet
end Publication
