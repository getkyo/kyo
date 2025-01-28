package kyo.offheap

import scala.scalanative.unsafe._
import scala.scalanative.libc.stdlib._

opaque type Arena = IO & Env[Arena.State]

object Arena:
    private type Allocation = Ptr[Byte]
    opaque type State = List[Allocation]
    
    given Tag[State] = Tag[List[Allocation]]

    def run[A: Flat, S](f: A < (Arena & S))(using Frame): A < (IO & S) =
        IO {
            var allocations = List.empty[Allocation]
            try
                Env.run(allocations)(f)
            finally
                allocations.foreach(free(_))
        }

    def use[A, S](f: List[Allocation] => A < S)(using Frame): A < (Arena & S) =
        Env.use[State](f)

    private[offheap] def allocate(size: Long)(using Frame): (Ptr[Byte], List[Allocation]) < Arena =
        Env.get[State].map { allocations =>
            val ptr = malloc(size)
            if ptr == null then
                throw new OutOfMemoryError(s"Failed to allocate $size bytes")
            (ptr, ptr :: allocations)
        }

  def open(): Arena =
    val arena = new Arena()
    arena.zone = Zone.open()
    arena

  def scoped[A](f: Arena => A): A =
    val arena = open()
    try f(arena)
    finally arena.close()
