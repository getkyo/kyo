package kyo

class Aeron

object Aeron:
  class Context:
    def aeronDirectoryName(): String = ""
  def connect(ctx: Context): Aeron = new Aeron
