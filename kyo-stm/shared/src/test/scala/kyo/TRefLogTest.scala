package kyo

import kyo.TRefLog.*

class TTRefLogTest extends Test:

    given [A, B]: CanEqual[A, B] = CanEqual.derived

    "TTRefLog" - {
        "empty" in run {
            val log = TRefLog.empty
            assert(log.toMap.isEmpty)
        }

        "put" in run {
            Sync.Unsafe {
                val tick  = STM.Tick.next()
                val ref   = new TRef[Int](Write(tick, 0))
                val entry = Write(tick, 42)
                val log   = TRefLog.empty.put(ref, entry)
                assert(log.toMap.size == 1)
                assert(log.toMap.head._1 == ref)
                assert(log.toMap.head._2 == entry)
            }
        }

        "get" in run {
            Sync.Unsafe {
                val tick  = STM.Tick.next()
                val ref   = new TRef[Int](Write(tick, 0))
                val entry = Write(tick, 42)
                val log   = TRefLog.empty.put(ref, entry)
                assert(log.get(ref) == Maybe(entry))
                assert(log.get(new TRef[Int](Write(tick, 0))).isEmpty)
            }
        }

        "toSeq" in run {
            Sync.Unsafe {
                val tick   = STM.Tick.next()
                val ref1   = new TRef[Int](Write(tick, 0))
                val ref2   = new TRef[Int](Write(tick, 0))
                val entry1 = Write(tick, 42)
                val entry2 = Read(tick, 24)

                val log = TRefLog.empty
                    .put(ref1, entry1)
                    .put(ref2, entry2)

                val seq = log.toMap.toSeq
                assert(seq.size == 2)
                assert(seq.contains((ref1, entry1)))
                assert(seq.contains((ref2, entry2)))
            }
        }
    }
end TTRefLogTest
