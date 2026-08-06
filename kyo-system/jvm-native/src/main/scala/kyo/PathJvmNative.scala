package kyo

import java.nio.file.Path as JPath
import kyo.internal.NioPathUnsafe

extension (self: Path)
    /** Returns the underlying `java.nio.file.Path`.
      *
      * Available on JVM and Scala Native only. The return type is precise — no cast required by the caller.
      */
    def toJava: JPath =
        self.unsafe match
            case nio: NioPathUnsafe => nio.jpath
            case other              => JPath.of(other.show)
end extension
