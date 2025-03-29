package kyo

import BufferClaim

class Publication:
  def tryClaim(size: Int, bufferClaim: BufferClaim): Int = Publication.BACK_PRESSURED
  def isConnected(): Boolean = false
  def close(): Unit = ()

object Publication:
  val BACK_PRESSURED = -1
  val NOT_CONNECTED  = -2
  val ADMIN_ACTION   = -3
  val CLOSED         = -4
