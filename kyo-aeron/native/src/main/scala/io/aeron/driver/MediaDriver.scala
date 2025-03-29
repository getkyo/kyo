package kyo

class MediaDriver:
  def aeronDirectoryName(): String = ""
  def close(): Unit = ()

object MediaDriver:
  def launchEmbedded(): MediaDriver = new MediaDriver
