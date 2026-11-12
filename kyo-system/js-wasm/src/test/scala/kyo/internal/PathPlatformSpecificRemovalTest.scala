package kyo.internal

import kyo.*

class PathPlatformSpecificRemovalTest extends kyo.test.Test[Any]:

    "removeAll deletes a broken symbolic link" in {
        Scope.run(Path.run {
            for
                dir <- Path.tempDir("kyo-remove-broken-link")
                link = dir / "link"
                _      <- Sync.defer(NodeFs.symlinkSync((dir / "missing").unsafe.show, link.unsafe.show))
                _      <- link.removeAll
                exists <- link.exists(followLinks = false)
            yield assert(!exists)
        })
    }

    "removeAll deletes a directory link without traversing its target" in {
        Scope.run(Path.run {
            for
                dir <- Path.tempDir("kyo-remove-directory-link")
                link   = dir / "link"
                target = dir / "target"
                file   = target / "keep.txt"
                _        <- file.write("keep me")
                _        <- Sync.defer(NodeFs.symlinkSync(target.unsafe.show, link.unsafe.show))
                _        <- link.removeAll
                exists   <- link.exists(followLinks = false)
                contents <- file.read
            yield
                assert(!exists)
                assert(contents == "keep me")
        })
    }
end PathPlatformSpecificRemovalTest
