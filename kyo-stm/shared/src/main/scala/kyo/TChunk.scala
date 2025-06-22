package kyo

/** A transactional wrapper around Chunk that provides atomic operations on sequences. All operations are performed within STM transactions
  * to ensure consistency.
  *
  * @tparam A
  *   the type of elements in the chunk
  */
opaque type TChunk[A] = TRef[Chunk[A]]

object TChunk:

    /** Creates a new empty TChunk.
      *
      * @return
      *   a new empty transactional chunk
      */
    def init[A](using Frame): TChunk[A] < Sync =
        init(Chunk.empty[A])

    /** Creates a new TChunk containing the provided values.
      *
      * @param values
      *   The initial values to store in the chunk
      * @return
      *   A new TChunk containing the values, within the Sync effect
      */
    def init[A](values: A*)(using Frame): TChunk[A] < Sync =
        init(Chunk.from(values))

    /** Creates a new TChunk from an existing Chunk.
      *
      * @param chunk
      *   The initial chunk to wrap
      * @return
      *   A new TChunk containing the chunk, within the Sync effect
      */
    def init[A](chunk: Chunk[A])(using Frame): TChunk[A] < Sync =
        initWith(chunk)(identity)

    /** Creates a new TChunk and immediately applies a function to it.
      *
      * This is a more efficient way to initialize a TChunk and perform operations on it, as it combines initialization and the first
      * operation in a single transaction.
      *
      * @param chunk
      *   The initial chunk to wrap
      * @param f
      *   The function to apply to the newly created TChunk
      * @return
      *   The result of applying the function to the new TChunk, within combined Sync and S effects
      */
    inline def initWith[A, B, S](chunk: Chunk[A])(inline f: TChunk[A] => B < S)(using inline frame: Frame): B < (Sync & S) =
        TRef.initWith(chunk)(f)

    private def useRef[A, B, S](ref: TRef[A], f: A => B < S)(using Frame) = ref.use(f(_))

    extension [A](self: TChunk[A])

        /** Returns the current size of the chunk.
          *
          * @return
          *   the number of elements in the chunk
          */
        def size(using Frame): Int < STM = self.use(_.length)

        /** Checks if the chunk is empty.
          *
          * @return
          *   true if the chunk contains no elements, false otherwise
          */
        def isEmpty(using Frame): Boolean < STM = self.use(_.isEmpty)

        /** Gets the element at the specified index.
          *
          * @param index
          *   the index of the element to return
          * @return
          *   the element at the specified index
          * @throws IndexOutOfBoundsException
          *   if the index is out of bounds
          */
        def get(index: Int)(using Frame): A < STM = self.use(_(index))

        /** Applies a function to the current state of the chunk within a transaction.
          *
          * @param f
          *   the function to apply to the chunk
          * @return
          *   the result of applying the function to the chunk, within the combined STM and S effects
          */
        def use[B, S](f: Chunk[A] => B < S)(using Frame): B < (STM & S) =
            useRef(self, f(_))

        /** Returns the first element of the chunk.
          *
          * @return
          *   the first element
          * @throws NoSuchElementException
          *   if the chunk is empty
          */
        def head(using Frame): A < STM = self.use(_.head)

        /** Returns the last element of the chunk.
          *
          * @return
          *   the last element
          * @throws NoSuchElementException
          *   if the chunk is empty
          */
        def last(using Frame): A < STM = self.use(_.last)

        /** Appends an element to the end of the chunk.
          *
          * @param value
          *   the element to append
          */
        def append(value: A)(using Frame): Unit < STM = self.update(_.append(value))

        /** Takes the first n elements of the chunk.
          *
          * @param n
          *   the number of elements to take
          */
        def take(n: Int)(using Frame): Unit < STM = self.update(_.take(n))

        /** Drops the first n elements of the chunk.
          *
          * @param n
          *   the number of elements to drop
          */
        def drop(n: Int)(using Frame): Unit < STM = self.update(_.drop(n))

        /** Drops the last n elements of the chunk.
          *
          * @param n
          *   the number of elements to drop from the end
          */
        def dropRight(n: Int)(using Frame): Unit < STM = self.update(_.dropRight(n))

        /** Returns a slice of the chunk between two indices.
          *
          * @param from
          *   the starting index (inclusive)
          * @param until
          *   the ending index (exclusive)
          */
        def slice(from: Int, until: Int)(using Frame): Unit < STM = self.update(_.slice(from, until))

        /** Concatenates another chunk to this one.
          *
          * @param other
          *   the chunk to concatenate
          */
        def concat(other: Chunk[A])(using Frame): Unit < STM = self.update(_.concat(other))

        /** Filters elements based on a predicate.
          *
          * @param p
          *   the predicate function
          */
        def filter[S](p: A => Boolean < S)(using Frame): Unit < (STM & S) =
            self.use { chunk =>
                Kyo.foldLeft(chunk.toSeq)(Chunk.empty[A]) { (acc, a) =>
                    p(a).map {
                        case true  => acc.append(a)
                        case false => acc
                    }
                }.map(self.set)
            }

        /** Compacts the chunk into an indexed representation to free references to unnecessary data.
          *
          * Many operations on Chunk (like take, drop, slice) maintain references to the original chunk even when only a portion of it is
          * needed. Calling compact creates a new indexed chunk containing just the required elements, allowing the garbage collector to
          * free unused portions of the original Chunk.
          *
          * @return
          *   unit in the STM effect, indicating the operation was performed transactionally
          */
        def compact(using Frame): Unit < STM = self.update(_.toIndexed)

        /** Gets a snapshot of the current chunk state.
          *
          * @return
          *   the current chunk
          */
        def snapshot(using Frame): Chunk[A] < STM = self.get
    end extension

end TChunk
