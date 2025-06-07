package io.aeron.driver

object MediaDriver:
    def launchEmbedded(): MediaDriver =
        new MediaDriver

class MediaDriver:
    def aeronDirectoryName(): String = ""
    def close(): Unit                = {}
