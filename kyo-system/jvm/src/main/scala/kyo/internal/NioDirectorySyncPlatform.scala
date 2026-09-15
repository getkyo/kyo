package kyo.internal

import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

private[kyo] object NioDirectorySyncPlatform:
    def sync(path: java.nio.file.Path): Unit =
        val channel = FileChannel.open(path, StandardOpenOption.READ)
        try channel.force(true)
        finally channel.close()
    end sync
end NioDirectorySyncPlatform
