package kyo

// Scratch reproduction artifact for the CI leaf hang (arm64 JVM job, run 29617762796): pure
// leaves stuck forever in the runner's per-leaf Fiber.initUnscoped + get await. Hammers the
// exact leaf lifecycle with many trivial bodies so a dropped completion wakeup surfaces as a
// STUCK leaf. Dev artifact: delete before any change is finished.
class ScratchLeafStressTest extends kyo.test.Test[Any]:
    for i <- 1 to 600 do
        s"leaf $i completes immediately" in {
            Sync.defer {
                val garbage = new Array[Byte](512 * 1024)
                garbage(i % garbage.length) = 1
                assert(garbage.length > i % 7)
            }
        }
    end for
end ScratchLeafStressTest
