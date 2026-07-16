package kyo

/** Construction and equality round-trip tests for the commit-conflict value types. Covers
  * `CommitConflict`, `Conflict`, `Resolution` (all four cases), `Path.Entry` (both variants),
  * `Path.Stamp`, and `Path.Stamp.Kind` (all three cases including `Absent`; `Path.Stamp` is
  * retained as a public type though the overlay read-set no longer constructs it). Also asserts
  * that `Conflict.ancestor` carries a `Maybe[Path.Entry]`: the read-set records the full observed
  * entry (bytes and stat for a regular file, stat for a directory) at observation time.
  */
class CommitConflictTest extends kyo.test.Test[Any]:

    val samplePath: Path          = Path("some", "file.txt")
    val sampleStat: Path.PathStat = Path.PathStat(lastModifiedMs = 1_000_000L, sizeBytes = 42L)
    val sampleBytes: Span[Byte]   = Span.from(Array[Byte](1, 2, 3))

    // --- Path.Stamp.Kind ---

    "Path.Stamp.Kind has three cases" in {
        val kinds = Path.Stamp.Kind.values
        assert(kinds.contains(Path.Stamp.Kind.File))
        assert(kinds.contains(Path.Stamp.Kind.Directory))
        assert(kinds.contains(Path.Stamp.Kind.Absent))
        assert(kinds.size == 3)
    }

    "Path.Stamp.Kind derives CanEqual" in {
        assert(Path.Stamp.Kind.File == Path.Stamp.Kind.File)
        assert(Path.Stamp.Kind.Directory == Path.Stamp.Kind.Directory)
        assert(Path.Stamp.Kind.Absent == Path.Stamp.Kind.Absent)
        assert(Path.Stamp.Kind.File != Path.Stamp.Kind.Directory)
    }

    // --- Path.Stamp ---

    "Path.Stamp construction with file kind and full fields" in {
        val stamp = Path.Stamp(
            entryType = Path.Stamp.Kind.File,
            size = Present(42L.bytes),
            lastModifiedMs = Present(1_000_000L),
            contentHash = Present(sampleBytes)
        )
        assert(stamp.entryType == Path.Stamp.Kind.File)
        assert(stamp.size == Present(42L.bytes))
        assert(stamp.lastModifiedMs == Present(1_000_000L))
        // Span[Byte] does not derive CanEqual; compare via toArrayUnsafe.
        stamp.contentHash match
            case Present(h) => assert(h.toArrayUnsafe sameElements sampleBytes.toArrayUnsafe)
            case Absent     => fail("contentHash should be Present")
    }

    "Path.Stamp construction with directory kind and absent optional fields" in {
        val stamp = Path.Stamp(
            entryType = Path.Stamp.Kind.Directory,
            size = Absent,
            lastModifiedMs = Absent,
            contentHash = Absent
        )
        assert(stamp.entryType == Path.Stamp.Kind.Directory)
        assert(stamp.size == Absent)
        assert(stamp.contentHash == Absent)
    }

    "Path.Stamp construction with Absent kind (observed-missing path)" in {
        val stamp = Path.Stamp(
            entryType = Path.Stamp.Kind.Absent,
            size = Absent,
            lastModifiedMs = Absent,
            contentHash = Absent
        )
        assert(stamp.entryType == Path.Stamp.Kind.Absent)
    }

    "Path.Stamp derives CanEqual" in {
        val s1 = Path.Stamp(Path.Stamp.Kind.File, Present(10L.bytes), Present(99L), Absent)
        val s2 = Path.Stamp(Path.Stamp.Kind.File, Present(10L.bytes), Present(99L), Absent)
        val s3 = Path.Stamp(Path.Stamp.Kind.Directory, Absent, Absent, Absent)
        assert(s1 == s2)
        assert(s1 != s3)
    }

    // --- Path.Entry ---

    "Path.Entry.File construction and CanEqual" in {
        val e1 = Path.Entry.File(sampleBytes, sampleStat)
        val e2 = Path.Entry.File(sampleBytes, sampleStat)
        assert(e1 == e2)
        // Access fields via pattern match since e1 is typed as Path.Entry (the enum parent).
        e1 match
            case Path.Entry.File(bytes, stat) =>
                assert(bytes.toArrayUnsafe sameElements sampleBytes.toArrayUnsafe)
                assert(stat == sampleStat)
            case _ => fail("expected Path.Entry.File")
        end match
    }

    "Path.Entry.Directory construction and CanEqual" in {
        val e1 = Path.Entry.Directory(sampleStat)
        val e2 = Path.Entry.Directory(sampleStat)
        assert(e1 == e2)
        e1 match
            case Path.Entry.Directory(stat) => assert(stat == sampleStat)
            case _                          => fail("expected Path.Entry.Directory")
    }

    "Path.Entry File and Directory are not equal" in {
        val f = Path.Entry.File(sampleBytes, sampleStat)
        val d = Path.Entry.Directory(sampleStat)
        assert(f != d)
    }

    "Conflict construction with Maybe[Path.Entry] ancestor" in {
        val entry    = Path.Entry.File(sampleBytes, sampleStat)
        val ours     = Present(Path.Entry.File(sampleBytes, sampleStat))
        val theirs   = Present(Path.Entry.Directory(sampleStat))
        val conflict = Conflict(samplePath, Present(entry), ours, theirs)
        assert(conflict.path == samplePath)
        assert(conflict.ancestor == Present(entry))
        assert(conflict.ours == ours)
        assert(conflict.theirs == theirs)
    }

    "Conflict with Absent ancestor (path was never observed)" in {
        val conflict = Conflict(samplePath, Absent, Absent, Absent)
        assert(conflict.ancestor == Absent)
        assert(conflict.ours == Absent)
        assert(conflict.theirs == Absent)
    }

    "Conflict derives CanEqual" in {
        val entry = Path.Entry.File(sampleBytes, sampleStat)
        val c1    = Conflict(samplePath, Present(entry), Absent, Absent)
        val c2    = Conflict(samplePath, Present(entry), Absent, Absent)
        val c3    = Conflict(samplePath, Absent, Absent, Absent)
        assert(c1 == c2)
        assert(c1 != c3)
    }

    // --- Resolution: all four cases ---

    "Resolution.KeepOurs derives CanEqual" in {
        assert(Resolution.KeepOurs == Resolution.KeepOurs)
    }

    "Resolution.KeepTheirs derives CanEqual" in {
        assert(Resolution.KeepTheirs == Resolution.KeepTheirs)
    }

    "Resolution.Write construction and CanEqual" in {
        val entry = Path.Entry.File(sampleBytes, sampleStat)
        val r1    = Resolution.Write(entry)
        val r2    = Resolution.Write(entry)
        assert(r1 == r2)
        // Access .entry via pattern match since r1 is typed as Resolution (the enum parent).
        r1 match
            case Resolution.Write(e) => assert(e == entry)
            case _                   => fail("expected Resolution.Write")
    }

    "Resolution.Remove derives CanEqual" in {
        assert(Resolution.Remove == Resolution.Remove)
    }

    "all four Resolution cases are distinct" in {
        val entry = Path.Entry.Directory(sampleStat)
        val cases = List(Resolution.KeepOurs, Resolution.KeepTheirs, Resolution.Write(entry), Resolution.Remove)
        assert(cases.distinct.size == 4)
    }

    // --- CommitConflict ---

    "CommitConflict construction and conflict list access" in {
        val entry    = Path.Entry.File(sampleBytes, sampleStat)
        val conflict = Conflict(samplePath, Present(entry), Absent, Absent)
        val cc       = CommitConflict(Chunk(conflict))
        assert(cc.conflicts.size == 1)
        assert(cc.conflicts.head == conflict)
    }

    "CommitConflict is a KyoException" in {
        val cc = CommitConflict(Chunk.empty)
        assert(cc.isInstanceOf[KyoException])
    }

end CommitConflictTest
