// kyo-offheap/shared/src/main/scala/kyo/Memory.scala
package kyo.offheap

import scala.annotation.implicitNotFound
import scala.annotation.tailrec
import scala.deriving.Mirror
import scala.scalanative.libc.stdlib.*
import scala.scalanative.unsafe.*

/** Memory provides a safe, effect-tracked interface for off-heap memory management for Scala Native. */
opaque type MemorySegment[A] = (Ptr[Byte], Long) // (pointer, element count)

export MemorySegment.Arena

object MemorySegment:
    given [A]: Flat[MemorySegment[A]] = Flat.derive

    /** Initializes a new memory segment for 'size' elements of type A */
    def init[A: Layout](size: Int)(using Frame): MemorySegment[A] < Arena =
        Arena.allocate(size * summon[Layout[A]].size).map { case (ptr, _) =>
            libc.string.memset(ptr, 0, size * summon[Layout[A]].size)
            (ptr, size.toLong)
        }

    /** Initializes memory and applies a function within Arena context */
    inline def initWith[A: Layout as l](size: Int)[B, S](inline f: MemorySegment[A] => B < S)(using inline frame: Frame): B < (Arena & S) =
        Arena.use { arena =>
            val byteSize = l.size * size
            val ptr      = malloc(byteSize)
            if ptr == null then
                throw new OutOfMemoryError(s"Failed to allocate $byteSize bytes")
            // Zero initialize memory
            libc.string.memset(ptr, 0, byteSize)
            IO.Unsafe(f((ptr, size.toLong)))
        }

    extension [A: Layout](self: MemorySegment[A])
        private inline def ptr: Ptr[Byte] = self._1
        private inline def size: Long     = self._2

        /** Safely retrieve element at index */
        def get(index: Int)(using Frame): A < Arena =
            IO.Unsafe {
                require(index >= 0 && index < size, s"Index out of bounds: $index")
                summon[Layout[A]].get(ptr, index * summon[Layout[A]].size)
            }

        /** Safely set element at index */
        def set(index: Int, value: A)(using Frame): Unit < Arena =
            IO.Unsafe {
                require(index >= 0 && index < size, s"Index out of bounds: $index")
                summon[Layout[A]].set(ptr, index * summon[Layout[A]].size, value)
            }

        /** Fill memory with value using optimized loop */
        inline def fill(value: A)(using inline frame: Frame): Unit < Arena =
            IO.Unsafe {
                var i = 0
                while i < size do
                    summon[Layout[A]].set(ptr, i * summon[Layout[A]].size, value)
                    i += 1
            }

        /** Fold over elements with bounds checking */
        inline def fold[B](zero: B)(f: (B, A) => B)(using inline frame: Frame): B < Arena =
            IO.Unsafe {
                var acc = zero
                var i   = 0
                while i < size do
                    acc = f(acc, summon[Layout[A]].get(ptr, i * summon[Layout[A]].size))
                    i += 1
                acc
            }

        /** Find index with bounds safety */
        inline def findIndex(p: A => Boolean)(using inline frame: Frame): Maybe[Int] < Arena =
            IO.Unsafe {
                var i = 0
                while i < size do
                    if p(summon[Layout[A]].get(ptr, i * summon[Layout[A]].size)) then
                        return Present(i)
                    i += 1
                end while
                Absent
            }

        /** Create view with range validation */
        def view(from: Int, len: Int)(using Frame): MemorySegment[A] < Arena =
            IO.Unsafe {
                require(from >= 0 && len >= 0 && from + len <= size, s"Invalid view range [$from, ${from + len}) of $size elements")
                (ptr + (from * summon[Layout[A]].size), len.toLong)
            }

        /** Copy with automatic bounds checking */
        def copy(from: Int, len: Int)(using Frame): MemorySegment[A] < Arena =
            Arena.allocate(len * summon[Layout[A]].size).map { case (newPtr, _) =>
                libc.string.memcpy(newPtr, ptr + (from * summon[Layout[A]].size), len * summon[Layout[A]].size)
                (newPtr, size)
            }

        /** Type-safe element count */
        def size: Long = size

        /** Low-level access with explicit safety */
        def unsafe: Unsafe[A] = (ptr, size)

        /** Check if any element satisfies a predicate */
        inline def exists(p: A => Boolean)(using inline frame: Frame): Boolean < Arena =
            IO.Unsafe {
                var i = 0
                while i < size do
                    if p(summon[Layout[A]].get(ptr, i * summon[Layout[A]].size)) then
                        return true
                    i += 1
                end while
                false
            }

        /** Copy elements to another memory segment */
        def copyTo(target: MemorySegment[A], srcPos: Int, destPos: Int, len: Int)(using Frame): Unit < Arena =
            IO.Unsafe {
                require(
                    srcPos >= 0 && destPos >= 0 && len >= 0 &&
                        srcPos + len <= size && destPos + len <= target.size,
                    "Invalid copy range"
                )
                libc.string.memcpy(
                    target.ptr + (destPos * summon[Layout[A]].size),
                    ptr + (srcPos * summon[Layout[A]].size),
                    len * summon[Layout[A]].size
                )
            }
    end extension

    /** Arena memory manager */
    opaque type Arena <: (Env[Arena.State] & IO) = Env[Arena.State] & IO

    object Arena:
        private type Allocation = Ptr[Byte]
        opaque type State       = Ptr[Ptr[Allocation]] // Head of allocation stack

        given Tag[State] = Tag[Ptr[Ptr[Allocation]]]

        /** Execute operation with auto-cleanup */
        def run[A: Flat, S](f: A < (Arena & S))(using Frame): A < (IO & S) =
            IO {
                val arena = stackalloc[Ptr[Allocation]]()
                !arena = null
                try Env.run(arena)(f)
                finally cleanup(arena)
            }

        private def cleanup(arena: Ptr[Ptr[Allocation]]): Unit =
            var current = !arena
            while current != null do
                val next = !current
                free(current)
                current = next
            end while
        end cleanup

        /** Allocate memory tracked by arena */
        def allocate(arena: Ptr[Ptr[Allocation]], size: CSize): Allocation =
            val block = malloc(size + sizeof[Ptr[Allocation]]) // Store next pointer
            if block == null then throw new OutOfMemoryError()
            !block = !arena // Prepend to allocation list
            !arena = block
            block + sizeof[Ptr[Allocation]] // Return data area after header
        end allocate

        private[kyo] def use[A, S](f: Ptr[Ptr[Allocation]] => A < S)(using Frame): A < (S & Arena) =
            Env.use[State](f)
    end Arena

    /** Unsafe low-level operations */
    opaque type Unsafe[A] = (Ptr[Byte], Long) // (pointer, element count)

    object Unsafe:
        extension [A: Layout as l](self: Unsafe[A])
            private inline def ptr: Ptr[Byte] = self._1
            private inline def size: Long     = self._2

            /** Direct get without bounds checking */
            inline def get(index: Int): A =
                l.get(ptr, index * l.size)

            /** Direct set without bounds checking */
            inline def set(index: Int, value: A): Unit =
                l.set(ptr, index * l.size, value)

            /** Convert back to safe API */
            def safe: MemorySegment[A] = (ptr, size)
        end extension
    end Unsafe

    /** Memory layout definitions */
    abstract class Layout[A]:
        inline def get(ptr: Ptr[Byte], offset: Long): A
        inline def set(ptr: Ptr[Byte], offset: Long, value: A): Unit
        def size: Long
    end Layout

    object Layout:
        given Layout[Byte] with
            inline def get(ptr: Ptr[Byte], offset: Long): Byte              = !(ptr + offset)
            inline def set(ptr: Ptr[Byte], offset: Long, value: Byte): Unit = !(ptr + offset) = value
            def size                                                        = sizeof[Byte]
        end given

        given Layout[Short] with
            inline def get(ptr: Ptr[Byte], offset: Long): Short              = !(ptr.at(offset).asInstanceOf[Ptr[Short]])
            inline def set(ptr: Ptr[Byte], offset: Long, value: Short): Unit = !(ptr.at(offset).asInstanceOf[Ptr[Short]]) = value
            def size                                                         = sizeof[Short]
        end given

        given Layout[Int] with
            inline def get(ptr: Ptr[Byte], offset: Long): Int              = !(ptr.at(offset).asInstanceOf[Ptr[Int]])
            inline def set(ptr: Ptr[Byte], offset: Long, value: Int): Unit = !(ptr.at(offset).asInstanceOf[Ptr[Int]]) = value
            def size                                                       = sizeof[Int]
        end given

        given Layout[Long] with
            inline def get(ptr: Ptr[Byte], offset: Long): Long              = !(ptr.at(offset).asInstanceOf[Ptr[Long]])
            inline def set(ptr: Ptr[Byte], offset: Long, value: Long): Unit = !(ptr.at(offset).asInstanceOf[Ptr[Long]]) = value
            def size                                                        = sizeof[Long]
        end given

        given Layout[Float] with
            inline def get(ptr: Ptr[Byte], offset: Long): Float              = !(ptr.at(offset).asInstanceOf[Ptr[Float]])
            inline def set(ptr: Ptr[Byte], offset: Long, value: Float): Unit = !(ptr.at(offset).asInstanceOf[Ptr[Float]]) = value
            def size                                                         = sizeof[Float]
        end given

        given Layout[Double] with
            inline def get(ptr: Ptr[Byte], offset: Long): Double              = !(ptr.at(offset).asInstanceOf[Ptr[Double]])
            inline def set(ptr: Ptr[Byte], offset: Long, value: Double): Unit = !(ptr.at(offset).asInstanceOf[Ptr[Double]]) = value
            def size                                                          = sizeof[Double]
        end given
    end Layout
end MemorySegment

trait Memory[A]:
    def copyTo(target: Memory[A], srcPos: Int, targetPos: Int, len: Int)(using AllowUnsafe): Unit
    def get(pos: Int)(using AllowUnsafe): A
    def set(pos: Int, value: A)(using AllowUnsafe): Unit
    // ... rest of the implementation
end Memory
