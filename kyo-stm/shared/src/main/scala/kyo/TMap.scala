package kyo

/** A transactional map implementation that provides atomic operations on key-value pairs within STM transactions. Internally represented as
  * `TRef[Map[K, TRef[V]]]`, where each value is wrapped in its own transactional reference.
  *
  * TMap is designed to minimize contention in concurrent scenarios through this nested TRef structure. Since each value has its own `TRef`,
  * operations on different keys can commit independently. Only structural changes like `put` with new keys or `remove` need to lock the
  * main map during commit, while value updates through `updateWith` or `put` to existing keys only lock that specific value's TRef.
  *
  * This architecture is particularly effective at reducing retries in concurrent scenarios by limiting the scope of conflicts between
  * transactions. Updates to different keys can proceed in parallel, while reads can commit concurrently with writes to different keys. The
  * implementation maintains strong consistency guarantees while allowing maximum concurrency for non-conflicting operations.
  *
  * Operations that modify existing values have low contention characteristics, while structural modifications experience higher contention.
  * This makes TMap particularly well-suited for scenarios with high concurrent access to different keys where most operations are reads or
  * updates to existing values.
  */
opaque type TMap[K, V] = TRef[Map[K, TRef[V]]]

object TMap:

    /** Creates a new empty TMap.
      *
      * @return
      *   A new empty TMap within the Sync effect
      */
    def init[K, V](using Frame): TMap[K, V] < Sync =
        TRef.init(Map.empty)

    /** Creates a new TMap containing the provided key-value pairs.
      *
      * @param entries
      *   The initial key-value pairs to store in the map
      * @return
      *   A new TMap containing the entries, within the Sync effect
      */
    def init[K, V](entries: (K, V)*)(using Frame): TMap[K, V] < Sync =
        initWith(entries*)(identity)

    /** Creates a new TMap from an existing Map.
      *
      * @param map
      *   The initial map to wrap
      * @return
      *   A new TMap containing the map entries, within the Sync effect
      */
    def init[K, V](map: Map[K, V])(using Frame): TMap[K, V] < Sync =
        init(map.toSeq*)

    /** Creates a new TMap and immediately applies a function to it.
      *
      * This is a more efficient way to initialize a TMap and perform operations on it, as it combines initialization and the first
      * operation in a single transaction.
      *
      * @param entries
      *   The initial key-value pairs to store in the map
      * @param f
      *   The function to apply to the newly created TMap
      * @return
      *   The result of applying the function to the new TMap, within combined Sync and S effects
      */
    inline def initWith[K, V](inline entries: (K, V)*)[A, S](inline f: TMap[K, V] => A < S)(
        using inline frame: Frame
    ): TMap[K, V] < (Sync & S) =
        TID.useIOUnsafe { tid =>
            val trefs =
                entries.foldLeft(Map.empty[K, TRef[V]]) {
                    case (acc, (k, v)) =>
                        acc.updated(k, TRef.Unsafe.init(tid, v))
                }
            TRef.Unsafe.init(tid, trefs)
        }

    extension [K, V](self: TMap[K, V])

        /** Applies a function to the value associated with a key if it exists.
          *
          * @param key
          *   the key to look up
          * @param f
          *   the function to apply to the value if found
          * @return
          *   the result of applying the function
          */
        def use[A, S](key: K)(f: Maybe[V] => A < S)(using Frame): A < (STM & S) =
            self.use { map =>
                if map.contains(key) then
                    map(key).use(v => f(Maybe(v)))
                else
                    f(Maybe.empty)
            }

        /** Returns the current size of the map.
          *
          * @return
          *   the number of key-value pairs in the map
          */
        def size(using Frame): Int < STM = self.use(_.size)

        /** Checks if the map is empty.
          *
          * @return
          *   true if the map contains no entries, false otherwise
          */
        def isEmpty(using Frame): Boolean < STM = self.use(_.isEmpty)

        /** Checks if the map is non-empty.
          *
          * @return
          *   true if the map contains at least one entry, false otherwise
          */
        def nonEmpty(using Frame): Boolean < STM = self.use(_.nonEmpty)

        /** Removes all entries from the map.
          */
        def clear(using Frame): Unit < STM = self.set(Map.empty)

        /** Retrieves the value associated with a key.
          *
          * @param key
          *   the key to look up
          * @return
          *   the value if found, or Maybe.empty if not present
          */
        def get(key: K)(using Frame): Maybe[V] < STM =
            use(key)(identity)

        /** Retrieves the value for a key, or evaluates a default if not found.
          *
          * @param key
          *   the key to look up
          * @param orElse
          *   the default value to compute if key is not found
          * @return
          *   the value associated with the key or the computed default
          */
        inline def getOrElse[A, S](key: K, inline orElse: => V < S)(using inline frame: Frame): V < (STM & S) =
            self.use(key) {
                case Absent     => orElse
                case Present(v) => v
            }

        /** Adds a new key-value pair to the map.
          *
          * @param key
          *   the key to add
          * @param value
          *   the value to associate with the key
          */
        def put(key: K, value: V)(using Frame): Unit < STM =
            self.use { map =>
                if map.contains(key) then
                    map(key).set(value)
                else
                    TRef.initWith(value) { ref =>
                        self.update(_.updated(key, ref))
                    }
            }

        /** Checks if a key exists in the map.
          *
          * @param key
          *   the key to check
          * @return
          *   true if the key exists, false otherwise
          */
        def contains(key: K)(using Frame): Boolean < STM =
            self.use(!_.isEmpty)

        /** Updates the value associated with a key based on its current value.
          *
          * @param key
          *   the key to update
          * @param f
          *   the function to transform the current value
          */
        def updateWith[S](key: K)(f: Maybe[V] => Maybe[V] < S)(using Frame): Unit < (STM & S) =
            use(key) { currentValue =>
                f(currentValue).map {
                    case Absent     => self.update(_ - key)
                    case Present(v) => put(key, v)
                }
            }

        /** Removes a key and returns its associated value if it existed.
          *
          * @param key
          *   the key to remove
          * @return
          *   the value that was associated with the key, if any
          */
        def remove(key: K)(using Frame): Maybe[V] < STM =
            use(key) {
                case Absent => Absent
                case Present(value) =>
                    self.update(_ - key).andThen(Maybe(value))
            }

        /** Removes a key without returning its value.
          *
          * @param key
          *   the key to remove
          */
        def removeDiscard(key: K)(using Frame): Unit < STM =
            use(key) {
                case Absent => ()
                case Present(value) =>
                    self.update(_ - key)
            }

        /** Removes multiple keys from the map.
          *
          * @param keys
          *   the sequence of keys to remove
          */
        def removeAll(keys: Seq[K])(using Frame): Unit < STM =
            self.use { map =>
                self.set(map.removedAll(keys))
            }

        /** Returns an iterable of all keys in the map.
          *
          * @return
          *   iterable containing all keys
          */
        def keys(using Frame): Iterable[K] < STM =
            self.use(_.keys)

        /** Returns an iterable of all values in the map.
          *
          * @return
          *   iterable containing all values
          */
        def values(using Frame): Iterable[V] < STM =
            self.use { map =>
                Kyo.collectAll(map.values.toSeq.map(_.get))
            }

        /** Returns an iterable of all key-value pairs in the map.
          *
          * @return
          *   iterable containing all entries
          */
        def entries(using Frame): Iterable[(K, V)] < STM =
            self.use { map =>
                Kyo.collectAll(
                    map.toSeq.map { case (k, ref) =>
                        ref.get.map((k, _))
                    }
                )
            }

        /** Removes entries that don't satisfy the given predicate.
          *
          * @param p
          *   the predicate function to test entries against
          */
        def filter[S](p: (K, V) => Boolean < S)(using Frame): Unit < (STM & S) =
            self.use { map =>
                Kyo.foreachDiscard(map.toSeq) { (key, ref) =>
                    ref.use { value =>
                        p(key, value).map {
                            case true  => ()
                            case false => removeDiscard(key)
                        }
                    }
                }
            }

        /** Folds over the entries in the map to produce a result.
          *
          * @param acc
          *   the initial accumulator value
          * @param f
          *   the function to combine the accumulator with each entry
          * @return
          *   the final accumulated result
          */
        def fold[A, B, S](acc: A)(f: (A, K, V) => A < S)(using Frame): A < (STM & S) =
            self.use { map =>
                Kyo.foldLeft(map.toSeq)(acc) {
                    case (acc, (key, ref)) =>
                        ref.use(v => f(acc, key, v))
                }
            }

        /** Finds the first entry that satisfies the given predicate.
          *
          * @param f
          *   the function to test entries
          * @return
          *   the first result that matches, if any
          */
        def findFirst[A, S](f: (K, V) => Maybe[A] < S)(using Frame): Maybe[A] < (STM & S) =
            self.use { map =>
                Kyo.findFirst(map.toSeq) { (key, ref) =>
                    ref.use(f(key, _))
                }
            }

        /** Creates an immutable snapshot of the current map state.
          *
          * @return
          *   a Map containing the current entries
          */
        def snapshot(using Frame): Map[K, V] < STM =
            entries.map(_.toMap)

    end extension
end TMap
