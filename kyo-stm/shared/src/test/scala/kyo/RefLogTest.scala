package kyo

import kyo.STM.internal.*

class RefLogTest extends Test:

    given [A, B]: CanEqual[A, B] = CanEqual.derived

    "RefLog" - {
        "empty" in run {
            val log = RefLog.empty
            assert(log.toSeq.isEmpty)
        }

        "put" in run {
            IO {
                val ref   = new TRefImpl[Int](Write(0, 0))
                val entry = Write(1, 42)
                val log   = RefLog.empty.put(ref, entry)
                assert(log.toSeq.size == 1)
                assert(log.toSeq.head._1 == ref)
                assert(log.toSeq.head._2 == entry)
            }
        }

        "get" in run {
            IO {
                val ref   = new TRefImpl[Int](Write(0, 0))
                val entry = Write(1, 42)
                val log   = RefLog.empty.put(ref, entry)
                assert(log.get(ref) == Maybe(entry))
                assert(log.get(new TRefImpl[Int](Write(0, 0))).isEmpty)
            }
        }

        "toSeq" in run {
            IO {
                val ref1   = new TRefImpl[Int](Write(0, 0))
                val ref2   = new TRefImpl[Int](Write(0, 0))
                val entry1 = Write(1, 42)
                val entry2 = Read(1, 24)

                val log = RefLog.empty
                    .put(ref1, entry1)
                    .put(ref2, entry2)

                val seq = log.toSeq
                assert(seq.size == 2)
                assert(seq.contains((ref1, entry1)))
                assert(seq.contains((ref2, entry2)))
            }
        }
    }
end RefLogTest
