package kyo

import java.lang.foreign.{Arena as JArena, *}
import scala.annotation.tailrec

/** Memory provides a safe, effect-tracked interface for off-heap memory management.
  *
  * Traditional off-heap memory management is error-prone, requiring manual deallocation and careful tracking of memory lifetimes. This
  * implementation leverages Kyo's effect system to guarantee proper resource cleanup through Arena-managed lifetimes. Additionally, while
  * off-heap operations are inherently untyped and unsafe, Memory[A] provides type-safe operations through Layout type classes, ensuring
  * memory reads and writes maintain type safety.
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
  * @tparam A
  *   The type of elements stored in this memory segment
  */
opaque type Memory[A] = MemorySegment

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
    def init[A: Layout as l](size: Int)(using Frame): Memory[A] < Arena =
        initWith[A](size)(identity)

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
    def initWith[A: Layout as l](size: Int)[B, S](f: Memory[A] => B < S)(using Frame): B < (Arena & S) =
        Arena.use { arena =>
            Sync.Unsafe(f(arena.allocate(l.size * size)))
        }

    extension [A: Layout as l](self: Memory[A])

        /** Retrieves the element at the specified index.
          *
          * @param index
          *   The position to read from
          * @return
          *   The element at the specified index
          */
        inline def get(index: Int)(using inline frame: Frame): A < Arena =
            Sync.Unsafe {
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
            Sync.Unsafe(Unsafe.set(self)(index, value))

        /** Fills the entire memory segment with the specified value.
          *
          * @param value
          *   The value to fill with
          */
        inline def fill(value: A)(using inline frame: Frame): Unit < Arena =
            Sync.Unsafe(Unsafe.fill(self)(value))

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
            Sync.Unsafe(Unsafe.fold(self)(zero)(f))

        /** Finds the index of the first element satisfying the predicate.
          *
          * @param p
          *   The predicate function
          * @return
          *   The index wrapped in Maybe, or Absent if not found
          */
        inline def findIndex(p: A => Boolean)(using inline frame: Frame): Maybe[Int] < Arena =
            Sync.Unsafe(Unsafe.findIndex(self)(p))

        /** Checks if any element satisfies the predicate.
          *
          * @param p
          *   The predicate function
          * @return
          *   true if any element satisfies the predicate
          */
        inline def exists(p: A => Boolean)(using inline frame: Frame): Boolean < Arena =
            Sync.Unsafe(Unsafe.exists(self)(p))

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
            Sync.Unsafe(Unsafe.view(self)(from, len))

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
            Sync.Unsafe(Unsafe.copy(self)(from, len))

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
            Sync.Unsafe(Unsafe.copyTo(self)(target, srcPos, targetPos, len))

        /** Returns the number of elements this memory segment can hold. */
        def size: Long = self.byteSize / l.size

        /** Converts this Memory to an Unsafe instance for low-level operations. */
        def unsafe: Unsafe[A] = self
    end extension

    /** Represents a memory arena that manages the lifecycle of allocated Memory segments. */
    opaque type Arena <: (Env[Arena.State] & Sync) = Env[Arena.State] & Sync

    object Arena:
        opaque type State = JArena

        /** Runs an operation that requires an Arena, ensuring proper cleanup.
          *
          * @param f
          *   The operation to run
          * @return
          *   The result of the operation
          */
        def run[A, S](f: A < (Arena & S))(using Frame): A < (Sync & S) =
            Sync {
                val arena = JArena.ofShared()
                Sync.ensure(Sync(arena.close))(Env.run(arena)(f))
            }

        given isolate: Isolate.Contextual[Arena, Sync] = Isolate.Contextual.derive[Arena, Sync]

        private[kyo] def use[A, S](f: JArena => A < S)(using Frame): A < (S & Arena) =
            Env.use[State](f)
    end Arena

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    opaque type Unsafe[A] = MemorySegment

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        extension [A: Layout as l](self: Unsafe[A])
            inline def get(index: Int)(using AllowUnsafe): A =
                l.get(self, index * l.size)

            inline def set(index: Int, value: A)(using AllowUnsafe): Unit =
                l.set(self, index * l.size, value)

            inline def fill(value: A)(using AllowUnsafe): Unit =
                val len = size
                @tailrec def loop(i: Int): Unit =
                    if i < len then
                        set(i, value)
                        loop(i + 1)
                loop(0)
            end fill

            inline def fold[B](z: B)(inline f: (B, A) => B)(using AllowUnsafe): B =
                val len = size
                @tailrec def loop(i: Int, acc: B): B =
                    if i < len then
                        loop(i + 1, f(acc, get(i)))
                    else acc
                loop(0, z)
            end fold

            inline def findIndex(inline f: A => Boolean)(using AllowUnsafe): Maybe[Int] =
                val len = size
                @tailrec def loop(i: Int): Maybe[Int] =
                    if i < len then
                        if f(get(i)) then Present(i)
                        else loop(i + 1)
                    else Absent
                loop(0)
            end findIndex

            inline def exists(inline f: A => Boolean)(using AllowUnsafe): Boolean =
                findIndex(f) match
                    case Present(_) => true
                    case Absent     => false

            def view(from: Int, len: Int)(using AllowUnsafe): Unsafe[A] =
                self.asSlice(from * l.size, len * l.size)

            def copy(from: Int, len: Int)(using Frame): Unsafe[A] < Arena =
                Arena.use { arena =>
                    val newMem = arena.allocate(l.size * len)
                    MemorySegment.copy(self, from * l.size, newMem, 0, len * l.size)
                    newMem
                }

            def copyTo(target: Unsafe[A], srcPos: Int, targetPos: Int, len: Int)(using AllowUnsafe): Unit =
                MemorySegment.copy(self, srcPos * l.size, target, targetPos * l.size, len * l.size)

            def size: Long = self.byteSize / l.size

            def safe: Memory[A] = self
        end extension
    end Unsafe

    /** Defines how values of type A are laid out in memory. */
    sealed abstract class Layout[A] extends Serializable:
        inline def get(memory: Unsafe[A], offset: Long)(using AllowUnsafe): A
        inline def set(memory: Unsafe[A], offset: Long, value: A)(using AllowUnsafe): Unit
        def size: Long
    end Layout

    /** Provides Layout implementations for primitive types. */
    object Layout:
        import ValueLayout.*

        given Layout[Byte] with
            inline def get(memory: Unsafe[Byte], offset: Long)(using AllowUnsafe): Byte =
                memory.get(JAVA_BYTE, offset)
            inline def set(memory: Unsafe[Byte], offset: Long, value: Byte)(using AllowUnsafe): Unit =
                memory.set(JAVA_BYTE, offset, value)
            def size = JAVA_BYTE.byteSize
        end given

        given Layout[Short] with
            inline def get(memory: Unsafe[Short], offset: Long)(using AllowUnsafe): Short =
                memory.get(JAVA_SHORT, offset)
            inline def set(memory: Unsafe[Short], offset: Long, value: Short)(using AllowUnsafe): Unit =
                memory.set(JAVA_SHORT, offset, value)
            def size = JAVA_SHORT.byteSize
        end given

        given Layout[Int] with
            inline def get(memory: Unsafe[Int], offset: Long)(using AllowUnsafe): Int =
                memory.get(JAVA_INT, offset)
            inline def set(memory: Unsafe[Int], offset: Long, value: Int)(using AllowUnsafe): Unit =
                memory.set(JAVA_INT, offset, value)
            def size = JAVA_INT.byteSize
        end given

        given Layout[Long] with
            inline def get(memory: Unsafe[Long], offset: Long)(using AllowUnsafe): Long =
                memory.get(JAVA_LONG, offset)
            inline def set(memory: Unsafe[Long], offset: Long, value: Long)(using AllowUnsafe): Unit =
                memory.set(JAVA_LONG, offset, value)
            def size = JAVA_LONG.byteSize
        end given

        given Layout[Float] with
            inline def get(memory: Unsafe[Float], offset: Long)(using AllowUnsafe): Float =
                memory.get(JAVA_FLOAT, offset)
            inline def set(memory: Unsafe[Float], offset: Long, value: Float)(using AllowUnsafe): Unit =
                memory.set(JAVA_FLOAT, offset, value)
            def size = JAVA_FLOAT.byteSize
        end given

        given Layout[Double] with
            inline def get(memory: Unsafe[Double], offset: Long)(using AllowUnsafe): Double =
                memory.get(JAVA_DOUBLE, offset)
            inline def set(memory: Unsafe[Double], offset: Long, value: Double)(using AllowUnsafe): Unit =
                memory.set(JAVA_DOUBLE, offset, value)
            def size = JAVA_DOUBLE.byteSize
        end given
    end Layout
end Memory
