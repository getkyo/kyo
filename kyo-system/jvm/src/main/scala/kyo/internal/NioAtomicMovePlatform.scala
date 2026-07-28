package kyo.internal

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kyo.Path

private[kyo] object NioAtomicMovePlatform:

    private[kyo] def move(source: java.nio.file.Path, target: java.nio.file.Path, replace: Path.Replace): Unit =
        val options =
            if replace == Path.Replace.Existing then Array(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            else Array(StandardCopyOption.ATOMIC_MOVE)
        Files.move(source, target, options*)
        ()
    end move

end NioAtomicMovePlatform
