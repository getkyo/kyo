package kyo

import java.nio.file.Files as JFiles
import java.nio.file.Paths as JPaths
import kyo.OverlayFileSystem.CanonicalPath.*

/** JVM-only tests for the overlay's canonical resolution, which needs real symbolic links.
  *
  * Resolution is an observation of the lower and is pinned on first observation. Resolving afresh on
  * every access would let a key change as the lower changes, so one logical file could acquire two
  * keys in the upper and split its staged state.
  */
class OverlayFileSystemJvmTest extends kyo.test.Test[Any]:

    private def nio(path: Path) = JPaths.get(path.parts.mkString("/"))

    "canonical resolves a path through a lower symbolic link" in {
        Scope.run {
            Path.run(Path.tempDir("overlay-canon-root")).map { root =>
                Path.run(Path.tempDir("overlay-canon-target")).map { target =>
                    val linkNio = nio(root).resolve("link")
                    // Unsafe: creates a JVM-level symlink to exercise resolution through the lower
                    Sync.Unsafe.defer(JFiles.createSymbolicLink(linkNio, nio(target))).andThen {
                        FileSystem.overlay(FileSystem.host).map { fs =>
                            // The concrete type carries `canonical`, which the public interface does not expose.
                            val overlay = fs.asInstanceOf[OverlayFileSystem[Sync]]
                            val through = root / "link" / "file.txt"
                            Path.runReadOnly(target.realPath).map { targetReal =>
                                overlay.canonical(through).map { resolved =>
                                    assert(
                                        resolved.parts == targetReal.parts.append("file.txt"),
                                        s"expected resolution through the link, got ${resolved.parts}"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "canonical pins a resolution on first observation when the lower changes underneath" in {
        Scope.run {
            Path.run(Path.tempDir("overlay-pin-root")).map { root =>
                Path.run(Path.tempDir("overlay-pin-first")).map { first =>
                    Path.run(Path.tempDir("overlay-pin-second")).map { second =>
                        val linkNio = nio(root).resolve("link")
                        // Unsafe: creates a JVM-level symlink that the test then repoints
                        Sync.Unsafe.defer(JFiles.createSymbolicLink(linkNio, nio(first))).andThen {
                            FileSystem.overlay(FileSystem.host).map { fs =>
                                val overlay = fs.asInstanceOf[OverlayFileSystem[Sync]]
                                val through = root / "link" / "file.txt"
                                overlay.canonical(through).map { before =>
                                    // Repoint the link at a different directory.
                                    // Unsafe: mutates the lower between the two observations
                                    Sync.Unsafe.defer {
                                        JFiles.delete(linkNio)
                                        discard(JFiles.createSymbolicLink(linkNio, nio(second)))
                                    }.andThen {
                                        overlay.canonical(through).map { after =>
                                            assert(
                                                after.parts == before.parts,
                                                s"resolution must stay pinned: before=${before.parts} after=${after.parts}"
                                            )
                                            // And confirm the lower really did change, so the test is not vacuous.
                                            Path.runReadOnly((root / "link").realPath).map { liveLink =>
                                                Path.runReadOnly(second.realPath).map { secondReal =>
                                                    assert(
                                                        liveLink.parts == secondReal.parts,
                                                        "the link should now point at the second directory"
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "canonical returns an unresolvable path unchanged" in {
        Scope.run {
            Path.run(Path.tempDir("overlay-canon-absent")).map { root =>
                FileSystem.overlay(FileSystem.host).map { fs =>
                    val overlay = fs.asInstanceOf[OverlayFileSystem[Sync]]
                    Path.runReadOnly(root.realPath).map { rootReal =>
                        val staged = root / "not-created-yet" / "file.txt"
                        overlay.canonical(staged).map { resolved =>
                            // No lower entry exists below root, so the resolvable ancestor is root itself
                            // and the remaining segments are re-appended.
                            assert(
                                resolved.parts == rootReal.parts.append("not-created-yet").append("file.txt"),
                                s"got ${resolved.parts}"
                            )
                        }
                    }
                }
            }
        }
    }

end OverlayFileSystemJvmTest
