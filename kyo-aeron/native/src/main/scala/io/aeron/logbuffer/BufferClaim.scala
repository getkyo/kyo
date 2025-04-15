package kyo

class BufferClaim:
  def buffer(): DummyBuffer = new DummyBuffer
  def offset(): Int = 0
  def commit(): Unit = ()
