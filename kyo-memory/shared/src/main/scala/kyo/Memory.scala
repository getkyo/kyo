package kyo

import kyo.internal.UnsafeBuffer
import kyo.internal.UnsafeLayout
import scala.annotation.tailrec

/** Memory provides a safe, effect-tracked interface for off-heap memory management.
  *
  * Traditional off-heap memory management is error-prone, requiring manual deallocation and careful tracking of memory lifetimes. This
  * implementation leverages Kyo's effect system to guarantee proper resource cleanup through Arena-managed lifetimes. Additionally, while
  * off-heap operations are inherently untyped and unsafe, Memory[A] provides type-safe operations through UnsafeLayout type classes,
  * ensuring memory reads and writes maintain type safety.
  *
  * Memory operations must be performed within an Arena.run block, which provides a scoped context for memory management. The Arena tracks
  * all allocations within its scope and automatically frees them when the block completes, whether normally or due to an error.
  *
  * IMPORTANT: Care must be taken to ensure Memory objects do not escape their Arena.run scope. While the type system tracks Arena effects,
  * it is possible to capture a Memory reference and attempt to use it after its Arena has closed. Such usage will result in runtime errors,
  * as the underlying memory will have been deallocated.
  *
  * The implementation is designed with performance in mind, with operations being inlined to avoid boxing and allocation overhead. All
  * potentially unsafe operations are tracked in the type system through effects, making it explicit which parts of your program perform
  * memory operations while ensuring they're properly contained within Arena contexts. When maximum performance is required, the unsafe API
  * allows dropping down to direct memory operations while keeping such operations explicitly marked.
  *
  * Note that memory allocation operations (init, initWith) are intentionally not provided in the Unsafe API. This ensures that all memory
  * allocations are tracked by the Arena effect, which is necessary for automatic resource cleanup.
  *
  * Platform backing: Memory is an opaque alias over kyo-data's UnsafeBuffer, so the concrete storage follows that buffer per platform. On
  * the JVM (java.lang.foreign MemorySegment) and Scala Native (malloc/free) the storage is genuinely off-heap, outside the GC, with
  * deterministic deallocation when the Arena closes. On Scala.js and Wasm (the Scala.js WebAssembly backend) the backing is a heap
  * ArrayBuffer reclaimed by the JS garbage collector: the Arena scoping and the whole typed API behave identically, but the storage is not
  * truly off-heap and the buffer's close is a no-op. Code that relies on deterministic, outside-the-GC deallocation should treat JS and Wasm
  * as a heap fallback.
  *
  * @tparam A
  *   The type of elements stored in this memory segment
  */
opaque type Memory[A] = UnsafeBuffer

export Memory.Arena

object Memory:

    /** Initializes a new memory segment of the specified size for type A.
      *
      * @tparam A
      *   The type of elements to store
      * @param size
      *   The number of elements the memory segment can hold
      * @return
      *   A new Memory instance within an Arena context
      */
    def init[A: UnsafeLayout as l](size: Int)(using Frame): Memory[A] < Arena =
        Arena.use { tracker =>
            Sync.Unsafe.defer {
                // TODO: on Scala.js and Wasm UnsafeBuffer.alloc returns a heap ArrayBuffer, not off-heap memory, so the
                // Arena's close is a no-op and deallocation is left to the GC. The planned follow-up is a Node-only
                // off-heap backing via koffi (koffi.alloc/free), which requires moving UnsafeBuffer into kyo-ffi (where
                // koffi lives) and making kyo-memory depend on kyo-ffi; the browser and Wasm backends keep this heap
                // fallback. See kyo-memory/README.md "Cross-platform behavior".
                val buf = UnsafeBuffer.alloc(size.toLong * l.size)
                tracker.register(buf)
                buf
            }
        }

    /** Initializes a memory segment and immediately applies a function to it.
      *
      * @tparam A
      *   The type of elements to store
      * @param size
      *   The number of elements the memory segment can hold
      * @param f
      *   The function to apply to the newly created memory
      * @return
      *   The result of applying f to the new memory segment
      */
    def initWith[A: UnsafeLayout as l](size: Int)[B, S](f: Memory[A] => B < S)(using Frame): B < (Arena & S) =
        Arena.use { tracker =>
            Sync.Unsafe.defer {
                // TODO: heap-backed on Scala.js pending the koffi off-heap backing described in init.
                val buf = UnsafeBuffer.alloc(size.toLong * l.size)
                tracker.register(buf)
                f(buf)
            }
        }

    extension [A: UnsafeLayout as l](self: Memory[A])

        /** Retrieves the element at the specified index.
          *
          * @param index
          *   The position to read from
          * @return
          *   The element at the specified index
          */
        inline def get(index: Int)(using inline frame: Frame): A < Arena =
            Sync.Unsafe.defer {
                checkIndex(self, index)
                // TODO inference issue without the val
                val x = Unsafe.get(self)(index)
                x
            }
        end get

        /** Sets the element at the specified index.
          *
          * @param index
          *   The position to write to
          * @param value
          *   The value to write
          */
        inline def set(index: Int, value: A)(using inline frame: Frame): Unit < Arena =
            Sync.Unsafe.defer {
                checkIndex(self, index)
                Unsafe.set(self)(index, value)
            }

        /** Fills the entire memory segment with the specified value.
          *
          * @param value
          *   The value to fill with
          */
        inline def fill(value: A)(using inline frame: Frame): Unit < Arena =
            Sync.Unsafe.defer(Unsafe.fill(self)(value))

        /** Folds over all elements in the memory segment.
          *
          * @param zero
          *   The initial value
          * @param f
          *   The combining function
          * @return
          *   The final accumulated value
          */
        inline def fold[B](zero: B)(f: (B, A) => B)(using inline frame: Frame): B < Arena =
            Sync.Unsafe.defer(Unsafe.fold(self)(zero)(f))

        /** Finds the index of the first element satisfying the predicate.
          *
          * @param p
          *   The predicate function
          * @return
          *   The index wrapped in Maybe, or Absent if not found
          */
        inline def findIndex(p: A => Boolean)(using inline frame: Frame): Maybe[Int] < Arena =
            Sync.Unsafe.defer(Unsafe.findIndex(self)(p))

        /** Checks if any element satisfies the predicate.
          *
          * @param p
          *   The predicate function
          * @return
          *   true if any element satisfies the predicate
          */
        inline def exists(p: A => Boolean)(using inline frame: Frame): Boolean < Arena =
            Sync.Unsafe.defer(Unsafe.exists(self)(p))

        /** Creates a view of a portion of this memory segment.
          *
          * @param from
          *   Starting index
          * @param len
          *   Number of elements to include
          * @return
          *   A new Memory instance viewing the specified range
          */
        def view(from: Int, len: Int)(using Frame): Memory[A] < Arena =
            Sync.Unsafe.defer {
                checkRange(self, from, len)
                Unsafe.view(self)(from, len)
            }

        /** Creates a copy of a portion of this memory segment.
          *
          * @param from
          *   Starting index
          * @param len
          *   Number of elements to copy
          * @return
          *   A new Memory instance containing the copied data
          */
        def copy(from: Int, len: Int)(using Frame): Memory[A] < Arena =
            Arena.use { tracker =>
                Sync.Unsafe.defer {
                    checkRange(self, from, len)
                    val byteLen = len.toLong * l.size
                    val newBuf  = UnsafeBuffer.alloc(byteLen)
                    tracker.register(newBuf)
                    self.copyTo(newBuf, from.toLong * l.size, 0L, byteLen)
                    newBuf
                }
            }

        /** Copies elements from this memory segment to another.
          *
          * @param target
          *   The destination memory segment
          * @param srcPos
          *   Starting position in the source
          * @param targetPos
          *   Starting position in the target
          * @param len
          *   Number of elements to copy
          */
        def copyTo(target: Memory[A], srcPos: Int, targetPos: Int, len: Int)(using Frame): Unit < Arena =
            Sync.Unsafe.defer {
                checkRange(self, srcPos, len)
                checkRange(target, targetPos, len)
                Unsafe.copyTo(self)(target, srcPos, targetPos, len)
            }

        /** Returns the number of elements this memory segment can hold. */
        def size: Long = self.byteSize / l.size

        /** Converts this Memory to an Unsafe instance for low-level operations. */
        def unsafe: Unsafe[A] = self
    end extension

    /** Represents a memory arena that manages the lifecycle of allocated Memory segments.
      *
      * Arena tracks UnsafeBuffer instances and closes them on scope exit, replacing the previous JVM-specific java.lang.foreign.Arena
      * approach with a cross-platform solution.
      */
    opaque type Arena <: (Env[Arena.State] & Sync) = Env[Arena.State] & Sync

    object Arena:

        /** Tracks UnsafeBuffer lifecycle -- manages buffer registration and bulk close. */
        final class State private[Arena] ():
            private val buffers                                = new java.util.concurrent.ConcurrentLinkedQueue[UnsafeBuffer]()
            private[kyo] def register(buf: UnsafeBuffer): Unit = discard(buffers.add(buf))
            private[kyo] def closeAll()(using AllowUnsafe): Unit =
                var b = buffers.poll()
                while b != null do
                    if !b.isClosed then b.close()
                    b = buffers.poll()
            end closeAll
        end State

        /** Runs an operation that requires an Arena, ensuring proper cleanup.
          *
          * @param f
          *   The operation to run
          * @return
          *   The result of the operation
          */
        def run[A, S](f: A < (Arena & S))(using Frame): A < (Sync & S) =
            Sync.defer {
                val tracker = new State()
                Sync.ensure(Sync.Unsafe.defer(tracker.closeAll()))(Env.run(tracker)(f))
            }

        given isolate: Isolate[Arena, Sync, Any] = Isolate.derive[Env[State] & Sync, Sync, Any]

        private[kyo] def use[A, S](f: State => A < S)(using Frame): A < (S & Arena) =
            Env.use[State](f)
    end Arena

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    opaque type Unsafe[A] = UnsafeBuffer

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        extension [A: UnsafeLayout as l](self: Unsafe[A])
            inline def get(index: Int)(using AllowUnsafe): A =
                checkNotClosed(self)
                l.read(self, index.toLong * l.size)

            inline def set(index: Int, value: A)(using AllowUnsafe): Unit =
                checkNotClosed(self)
                l.write(self, index.toLong * l.size, value)

            inline def fill(value: A)(using AllowUnsafe): Unit =
                checkNotClosed(self)
                val len = size
                @tailrec def loop(i: Int): Unit =
                    if i < len then
                        l.write(self, i.toLong * l.size, value)
                        loop(i + 1)
                loop(0)
            end fill

            inline def fold[B](z: B)(inline f: (B, A) => B)(using AllowUnsafe): B =
                checkNotClosed(self)
                val len = size
                @tailrec def loop(i: Int, acc: B): B =
                    if i < len then
                        loop(i + 1, f(acc, l.read(self, i.toLong * l.size)))
                    else acc
                loop(0, z)
            end fold

            inline def findIndex(inline f: A => Boolean)(using AllowUnsafe): Maybe[Int] =
                checkNotClosed(self)
                val len = size
                @tailrec def loop(i: Int): Maybe[Int] =
                    if i < len then
                        if f(l.read(self, i.toLong * l.size)) then Present(i)
                        else loop(i + 1)
                    else Absent
                loop(0)
            end findIndex

            inline def exists(inline f: A => Boolean)(using AllowUnsafe): Boolean =
                findIndex(f) match
                    case Present(_) => true
                    case Absent     => false

            def view(from: Int, len: Int)(using AllowUnsafe): Unsafe[A] =
                checkNotClosed(self)
                self.view(from.toLong * l.size, len.toLong * l.size)

            def copy(from: Int, len: Int)(using Frame): Unsafe[A] < Arena =
                Arena.use { tracker =>
                    Sync.Unsafe.defer {
                        checkNotClosed(self)
                        val byteLen = len.toLong * l.size
                        val newBuf  = UnsafeBuffer.alloc(byteLen)
                        tracker.register(newBuf)
                        self.copyTo(newBuf, from.toLong * l.size, 0L, byteLen)
                        newBuf
                    }
                }

            def copyTo(target: Unsafe[A], srcPos: Int, targetPos: Int, len: Int)(using AllowUnsafe): Unit =
                checkNotClosed(self)
                self.copyTo(target, srcPos.toLong * l.size, targetPos.toLong * l.size, len.toLong * l.size)

            def size: Long = self.byteSize / l.size

            def safe: Memory[A] = self
        end extension
    end Unsafe

    private inline def checkNotClosed(buf: UnsafeBuffer): Unit =
        if buf.isClosed then throw new IllegalStateException("Memory accessed after Arena was closed")

    // Bounds validation for the safe API. The unsafe tier stays unchecked as the hot-path escape hatch, so these run
    // only on the safe extension methods, where they raise a managed IndexOutOfBoundsException uniformly on every
    // platform (the native backing does no bounds checking of its own).
    private def checkIndex[A](self: Memory[A], index: Int)(using l: UnsafeLayout[A]): Unit =
        val size = self.byteSize / l.size
        if index < 0 || index >= size then
            throw new IndexOutOfBoundsException(s"Memory index $index out of bounds for size $size")
    end checkIndex

    private def checkRange[A](self: Memory[A], from: Int, len: Int)(using l: UnsafeLayout[A]): Unit =
        val size = self.byteSize / l.size
        if from < 0 || len < 0 || from.toLong + len > size then
            throw new IndexOutOfBoundsException(s"Memory range [$from, ${from.toLong + len}) out of bounds for size $size")
    end checkRange
end Memory
