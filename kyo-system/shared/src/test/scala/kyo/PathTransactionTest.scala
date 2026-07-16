package kyo

import scala.compiletime.testing.typeCheckErrors

/** Tests for the [[Path.transaction]], [[Path.sandbox]], and [[Path.virtual]] combinators.
  *
  * Covers the commit-on-success disposition (transaction), discard-always disposition (sandbox),
  * and deferred-commit pattern (virtual), plus nested transaction composition. All cases use
  * in-memory lower for determinism. No Thread.sleep anywhere.
  */
class PathTransactionTest extends kyo.test.Test[Any]:

    // transaction commits on success, discards on abort, surfaces CommitConflict

    "transaction commits writes to lower on success" in {
        FileSystem.inMemory.map { lower =>
            val p = Path("tx-commit.txt")
            Abort.run[CommitConflict](
                Path.runWith(lower)(
                    Path.transaction(p.write("hello"))
                )
            ).map {
                case Result.Success(()) =>
                    Path.runWith(lower)(p.read).map(v => assert(v == "hello"))
                case Result.Failure(cc) =>
                    assert(false, s"unexpected CommitConflict: $cc")
            }
        }
    }

    "transaction discards staged writes when program aborts" in {
        FileSystem.inMemory.map { lower =>
            val p = Path("tx-abort.txt")
            Abort.run[String](
                Abort.run[CommitConflict](
                    Path.runWith(lower)(
                        Path.transaction(
                            p.write("staged").andThen(Abort.fail[String]("aborted"))
                        )
                    )
                )
            ).map { _ =>
                Path.runWith(lower)(p.exists).map(e => assert(!e))
            }
        }
    }

    "transaction surfaces CommitConflict when lower diverges after read" in {
        FileSystem.inMemory.map { lower =>
            val p = Path("tx-conflict.txt")
            Path.runWith(lower)(p.write("original")).andThen {
                Abort.run[CommitConflict](
                    Path.runWith(lower)(
                        Path.transaction(
                            // Read through overlay (records stamp), stage a write, then mutate
                            // lower directly via an inner runWith to force a stamp divergence
                            p.read.andThen(
                                p.write("staged").andThen(
                                    Path.runWith(lower)(p.write("lower-diverged"))
                                )
                            )
                        )
                    )
                ).map {
                    case Result.Failure(cc) =>
                        assert(cc.conflicts.size == 1)
                        assert(cc.conflicts.head.path == p)
                        // Lower must reflect only the direct mutation, not the staged overlay write
                        Path.runWith(lower)(p.read).map(v => assert(v == "lower-diverged"))
                    case _ =>
                        assert(false, "expected CommitConflict")
                }
            }
        }
    }

    // sandbox discards writes and never surfaces CommitConflict

    "sandbox discards staged writes on program success" in {
        FileSystem.inMemory.map { lower =>
            val p = Path("sandbox-discard.txt")
            Path.runWith(lower)(
                Path.sandbox(p.write("staged"))
            ).andThen {
                Path.runWith(lower)(p.exists).map(e => assert(!e))
            }
        }
    }

    "sandbox return type lacks Abort[CommitConflict]" in {
        // Positive: locked sandbox row is PathWrite & S with S that does not subsume Sync
        val errors1 = typeCheckErrors("""
            given kyo.Frame = kyo.Frame.internal
            val prog: Unit < kyo.PathWrite = ???
            val _: Unit < kyo.PathWrite = kyo.Path.sandbox(prog)
            val _: Unit < (kyo.PathWrite & kyo.Abort[String]) =
                kyo.Path.sandbox[Unit, kyo.Abort[String]](prog)
            """)
        assert(errors1.isEmpty, s"sandbox return type should match locked sandbox row but got: $errors1")
        // Negative: transaction result does not fit PathWrite alone (it adds Abort[CommitConflict])
        val errors2 = typeCheckErrors("""
            given kyo.Frame = kyo.Frame.internal
            val prog: Unit < kyo.PathWrite = ???
            val _: Unit < kyo.PathWrite = kyo.Path.transaction(prog)
            """)
        assert(errors2.nonEmpty, "transaction return type should include Abort[CommitConflict]")
    }

    // virtual returns the overlay for caller-controlled commit or discard

    "virtual commits writes to lower when caller commits the returned overlay" in {
        FileSystem.inMemory.map { lower =>
            val p = Path("virtual-commit.txt")
            Abort.run[CommitConflict](
                Path.runWith(lower)(
                    Path.virtual(p.write("hello")).map { case ((), overlay) =>
                        overlay.commit
                    }
                )
            ).andThen {
                Path.runWith(lower)(p.read).map(v => assert(v == "hello"))
            }
        }
    }

    "virtual discards writes when caller drops the overlay without committing" in {
        FileSystem.inMemory.map { lower =>
            val p = Path("virtual-drop.txt")
            Path.runWith(lower)(
                Path.virtual(p.write("hello")).map { case ((), _) => () }
            ).andThen {
                Path.runWith(lower)(p.exists).map(e => assert(!e))
            }
        }
    }

    // nested transactions compose as overlays

    "nested transactions stage inner writes in outer overlay; lower updated only after outer commit" in {
        FileSystem.inMemory.map { lower =>
            val p1 = Path("nest-outer.txt")
            val p2 = Path("nest-inner.txt")
            Abort.run[CommitConflict](
                Path.runWith(lower)(
                    Path.transaction(
                        p1.write("outer").andThen(
                            Path.transaction(
                                p2.write("inner")
                            ).andThen(
                                // After inner commit, outer overlay must see p2 via read
                                p2.read.map(v => assert(v == "inner"))
                            )
                        )
                    )
                )
            ).andThen {
                // Lower must now reflect both writes from the outer commit
                Path.runWith(lower)(p1.read).map(v1 => assert(v1 == "outer")).andThen(
                    Path.runWith(lower)(p2.read).map(v2 => assert(v2 == "inner"))
                )
            }
        }
    }

    // commit-scope boundary: overlay returned by virtual must be used inside a PathWrite scope

    "virtual commit outside ambient path scope leaves PathWrite unresolved" in {
        FileSystem.inMemory.map { lower =>
            val p = Path("virtual-scope-boundary.txt")
            // PathWrite is discharged by runWith here; overlay escapes with no handler in scope
            Path.runWith(lower)(Path.virtual(p.write("staged"))).map { case ((), overlay) =>
                // overlay.commit types as Unit < (Sync & Abort[FileException] & Abort[CommitConflict])
                // but at runtime re-suspends Tag[PathWrite] with no ambient handler.
                // evalNow returns absent, confirming the PathWrite suspension is unresolved.
                val computation = Abort.run[CommitConflict](Abort.run[FileException](overlay.commit))
                assert(computation.evalNow.isEmpty, "commit outside scope must leave PathWrite unresolved")
                // Lower is untouched: no commit reached the backend
                Path.runWith(lower)(p.exists).map(e => assert(!e))
            }
        }
    }

    // nesting composition: sandbox inside transaction and transaction inside sandbox

    "sandbox inside transaction discards sandboxed writes while transaction commits its own writes" in {
        FileSystem.inMemory.map { lower =>
            val pOuter     = Path("sit-outer.txt")
            val pSandboxed = Path("sit-sandboxed.txt")
            Abort.run[CommitConflict](
                Path.runWith(lower)(
                    Path.transaction(
                        pOuter.write("outer").andThen(
                            Path.sandbox(pSandboxed.write("sandboxed"))
                        )
                    )
                )
            ).map {
                case Result.Success(()) =>
                    // transaction committed pOuter; sandbox discarded pSandboxed
                    Path.runWith(lower)(pOuter.read).map(v => assert(v == "outer")).andThen(
                        Path.runWith(lower)(pSandboxed.exists).map(e => assert(!e))
                    )
                case Result.Failure(cc) =>
                    assert(false, s"unexpected CommitConflict: $cc")
            }
        }
    }

    "transaction inside sandbox stages writes that sandbox discards; lower unchanged after rollback" in {
        FileSystem.inMemory.map { lower =>
            val p = Path("tis-inner.txt")
            // sandbox is outermost: inner transaction commits into sandbox overlay,
            // then sandbox rolls back; lower never receives the writes
            Abort.run[CommitConflict](
                Path.runWith(lower)(
                    Path.sandbox(Path.transaction(p.write("inner")))
                )
            ).map {
                case Result.Success(()) =>
                    Path.runWith(lower)(p.exists).map(e => assert(!e))
                case Result.Failure(cc) =>
                    assert(false, s"unexpected CommitConflict: $cc")
            }
        }
    }

end PathTransactionTest
