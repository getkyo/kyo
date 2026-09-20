package kyo.internal.sqlite

import kyo.discard
import kyo.ffi.internal.KoffiFn
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/** Backs the SQLite bindings with a WebAssembly build instead of a loaded shared library.
  *
  * A generated JS impl calls `facade.<name>(args)` on a bag of callables. On Node that bag is koffi bound to a real
  * `.dylib`; where there is no koffi and no library to load, which is every browser, this builds the same bag over
  * a WebAssembly module.
  *
  * Every call goes through `wasm.exports` rather than the friendlier `sqlite3.capi.*`, whose re-marshalling is
  * wrong for this driver: `capi.sqlite3_column_text` hands back a JS String where the C function returns a
  * pointer, and a String cannot carry the embedded NUL bytes SQLite stores. Lengths come from
  * `sqlite3_column_bytes`, the only length correct for both text and blobs.
  *
  * The `kyo_sqlite3_*` symbols are this build's own wrappers from `kyo_sqlite.c`, which a stock WebAssembly build
  * does not carry, so each is rebuilt below over the raw exports. They must mirror that C exactly; where the two
  * disagree the C is right and this is the bug.
  */
object SqliteWasmFacade:

    /** SQLite's `SQLITE_TRANSIENT`: the destructor value that tells SQLite to copy before returning. */
    private val Transient = -1

    private val SqliteOk       = 0
    private val DbConfigDqsDml = 1013
    private val DbConfigDqsDdl = 1014

    /** Builds the dispatch table for `sqlite3`, an initialized Emscripten SQLite module. A symbol no implementation
      * here covers fails loudly rather than leaving a hole in the bag.
      */
    def provider(sqlite3: js.Dynamic): Seq[KoffiFn] => js.Dynamic =
        fns =>
            val wasm  = sqlite3.selectDynamic("wasm")
            val capi  = sqlite3.selectDynamic("capi")
            val calls = new Calls(wasm, capi)
            val bag   = js.Dynamic.literal()
            fns.foreach { fn =>
                calls.bySymbol.get(fn.cSymbol) match
                    case Some(impl) => bag.updateDynamic(fn.scalaName)(withAsync(impl, fn.args.size))
                    case None       =>
                        throw new UnsupportedOperationException(
                            s"The WebAssembly transport has no implementation for '${fn.cSymbol}'. Every symbol a " +
                                "binding names must be either a raw export of the module or one of the kyo_sqlite3_* " +
                                "wrappers rebuilt in SqliteWasmFacade."
                        )
            }
            bag

    /** A pointer result, in the shape the generated code reads.
      *
      * koffi hands back a NULL `void*` as JS `null`, and the generated marshal tests exactly that: a result which
      * is neither `undefined` nor `null` becomes a live handle. WebAssembly has no such convention, a null pointer
      * being simply the number 0, so returning it raw leaves `Absent` unreachable and turns every refusal into a
      * handle pointing at address zero.
      */
    private def pointer(p: Int): js.Any = if p == 0 then null else p

    /** Gives a plain function the `.async(...args, cb)` shape koffi handles carry.
      *
      * A binding marked `@Ffi.blocking` is dispatched through that shape, because on the native transport koffi
      * runs it on a libuv worker while the calling thread goes on. WebAssembly has no worker to hand it to, so
      * this adapts only the shape: on this transport a blocking call does block, and a long query holds the page.
      *
      * Built per arity rather than with a variadic JS function, because the only ways to make one of those are
      * `eval` and `new Function`, and a browser serving a Content-Security-Policy refuses both.
      */
    private def withAsync(impl: js.Any, arity: Int): js.Any =
        val fn = impl.asInstanceOf[js.Dynamic]
        fn.updateDynamic("async")(asyncAdapter(fn, arity))
        fn
    end withAsync

    private type Callback = js.Function2[js.Any, js.Any, Unit]

    /** Runs `body`, then hands its outcome to `cb` in koffi's `(err, result)` order. */
    private def settle(cb: Callback)(body: => js.Any): Unit =
        val outcome: (js.Any, js.Any) =
            try (null, body)
            catch case t: Throwable => (t.asInstanceOf[js.Any], js.undefined)
        defer(() => discard(cb(outcome._1, outcome._2)))
    end settle

    /** Runs `f` immediately.
      *
      * Deferring the completion does not work on this transport: neither a microtask nor a macrotask is delivered,
      * because nothing here yields to the event loop between the call and its result.
      */
    private def defer(f: () => Unit): Unit = f()

    private def asyncAdapter(fn: js.Dynamic, arity: Int): js.Any =
        arity match
            case 0 => js.Any.fromFunction1((cb: Callback) => settle(cb)(fn.applyDynamic("call")(fn)))
            case 1 => js.Any.fromFunction2((a0: js.Any, cb: Callback) => settle(cb)(fn.applyDynamic("call")(fn, a0)))
            case 2 => js.Any.fromFunction3((a0: js.Any, a1: js.Any, cb: Callback) => settle(cb)(fn.applyDynamic("call")(fn, a0, a1)))
            case 3 =>
                js.Any.fromFunction4((a0: js.Any, a1: js.Any, a2: js.Any, cb: Callback) =>
                    settle(cb)(fn.applyDynamic("call")(fn, a0, a1, a2))
                )
            case 4 =>
                js.Any.fromFunction5((a0: js.Any, a1: js.Any, a2: js.Any, a3: js.Any, cb: Callback) =>
                    settle(cb)(fn.applyDynamic("call")(fn, a0, a1, a2, a3))
                )
            case 5 =>
                js.Any.fromFunction6((a0: js.Any, a1: js.Any, a2: js.Any, a3: js.Any, a4: js.Any, cb: Callback) =>
                    settle(cb)(fn.applyDynamic("call")(fn, a0, a1, a2, a3, a4))
                )
            case n =>
                throw new UnsupportedOperationException(
                    s"A blocking binding of arity $n has no async adapter. Add one beside the others; they are " +
                        "written per arity because a browser's Content-Security-Policy refuses the variadic forms."
                )

    /** The implementations, closed over one module's heap and exports. */
    final private class Calls(wasm: js.Dynamic, capi: js.Dynamic):

        private def exports: js.Dynamic = wasm.selectDynamic("exports")

        private def call(name: String, args: js.Any*): js.Dynamic =
            exports.applyDynamic(name)(args*)

        // --- Heap helpers ---

        private def heap: Uint8Array = wasm.applyDynamic("heap8u")().asInstanceOf[Uint8Array]

        private def alloc(n: Int): Int = wasm.applyDynamic("alloc")(n).asInstanceOf[Int]

        private def dealloc(ptr: Int): Unit = discard(wasm.applyDynamic("dealloc")(ptr))

        private def allocCString(s: String): Int = wasm.applyDynamic("allocCString")(s).asInstanceOf[Int]

        private def cstrToJs(ptr: Int): String =
            if ptr == 0 then null else wasm.applyDynamic("cstrToJs")(ptr).asInstanceOf[String]

        private def peekPtr(ptr: Int): Int = wasm.applyDynamic("peekPtr")(ptr).asInstanceOf[Int]

        /** Runs `body` with `bytes` copied into a fresh heap block, freeing it afterwards whatever happens. */
        private def withBytes[A](bytes: Uint8Array, n: Int)(body: Int => A): A =
            val ptr = alloc(if n > 0 then n else 1)
            try
                if n > 0 then heap.set(bytes.subarray(0, n), ptr)
                body(ptr)
            finally dealloc(ptr)
            end try
        end withBytes

        private def withCString[A](s: String)(body: Int => A): A =
            val ptr = allocCString(s)
            try body(ptr)
            finally dealloc(ptr)
        end withCString

        /** Runs `body` with a pointer-sized out slot, answering what the callee wrote into it. */
        private def withOutPtr[A](body: Int => A): (A, Int) =
            val slot = alloc(8)
            try
                val a = body(slot)
                (a, peekPtr(slot))
            finally dealloc(slot)
            end try
        end withOutPtr

        /** Copies up to `cap` bytes from heap `src` into the caller's `dst`. The caller returns the FULL length,
          * which is how a short buffer is detected rather than silently truncated.
          */
        private def copyOut(src: Int, n: Int, dst: Uint8Array, cap: Int): Unit =
            if src != 0 && cap > 0 && n > 0 then
                val take = math.min(n, cap)
                dst.set(heap.subarray(src, src + take), 0)

        // --- The kyo_sqlite3_* wrappers, rebuilt ---

        /** Opens a connection and answers the handle alone.
          *
          * An empty `vfs` means the default one, because a null cannot reach here: a String argument is marshalled
          * by reading its bytes, so a null throws on the Scala side before the call. Absent is spelled "" at this
          * boundary and translated back to NULL, as the C wrapper does.
          */
        private val openV2: js.Function3[String, Int, String, js.Any] = (filename, flags, vfs) =>
            withCString(filename) { pName =>
                val (_, db) = withOutPtr { ppDb =>
                    if vfs != null && vfs.nonEmpty then
                        withCString(vfs)(pVfs => call("sqlite3_open_v2", pName, ppDb, flags, pVfs))
                    else call("sqlite3_open_v2", pName, ppDb, flags, 0)
                }
                pointer(db)
            }

        /** Prepares exactly ONE statement, refusing a string that holds more.
          *
          * The refusal cannot travel back beside the handle, so it is encoded in the pair: a non-zero handle means
          * one statement compiled; zero with the connection's error code clear means the string held a trailing
          * statement and nothing was compiled; zero with an error code set is an ordinary SQL error. Trailing
          * whitespace and semicolons are not a statement.
          */
        private val prepareOne: js.Function3[Int, String, Int, js.Any] = (db, sql, nByte) =>
            withCString(sql) { pSql =>
                val slotStmt = alloc(8)
                val slotTail = alloc(8)
                try
                    val rc = call("sqlite3_prepare_v2", db, pSql, nByte, slotStmt, slotTail).asInstanceOf[Int]
                    if rc != SqliteOk then pointer(0)
                    else
                        val stmt = peekPtr(slotStmt)
                        val tail = peekPtr(slotTail)
                        if tail == 0 then pointer(stmt)
                        else
                            val h   = heap
                            var i   = tail
                            var end = false
                            while !end do
                                val b = h.get(i).toInt & 0xff
                                if b == ' '.toInt || b == '\t'.toInt || b == '\n'.toInt || b == '\r'.toInt || b == ';'.toInt then i += 1
                                else end = true
                            end while
                            if (h.get(i).toInt & 0xff) != 0 then
                                discard(call("sqlite3_finalize", stmt))
                                pointer(0)
                            else pointer(stmt)
                            end if
                        end if
                    end if
                finally
                    dealloc(slotTail)
                    dealloc(slotStmt)
                end try
            }

        /** The int-valued `sqlite3_db_config` operations, fixed at two arguments.
          *
          * Through `capi` rather than a raw export: the C function is variadic, so it is not exported as a plain
          * function. Its arguments here are ints, which the wrapper passes through unchanged.
          */
        private val dbConfigInt: js.Function3[Int, Int, Int, Int] = (db, op, value) =>
            capi.applyDynamic("sqlite3_db_config")(db, op, value, 0).asInstanceOf[Int]

        /** Applies the session settings the driver requires, in one call, returning the FIRST failure. */
        private val configureConnection: js.Function2[Int, Int, Int] = (db, busyTimeoutMillis) =>
            var rc = dbConfigInt(db, DbConfigDqsDml, 0)
            if rc != SqliteOk then rc
            else
                rc = dbConfigInt(db, DbConfigDqsDdl, 0)
                if rc != SqliteOk then rc
                else
                    rc = call("sqlite3_extended_result_codes", db, 1).asInstanceOf[Int]
                    if rc != SqliteOk then rc
                    else
                        rc = execSimpleRaw(db, "PRAGMA foreign_keys=ON")
                        if rc != SqliteOk then rc
                        else
                            rc = execSimpleRaw(db, "PRAGMA trusted_schema=OFF")
                            if rc != SqliteOk then rc
                            else call("sqlite3_busy_timeout", db, busyTimeoutMillis).asInstanceOf[Int]
                        end if
                    end if
                end if
            end if

        private def execSimpleRaw(db: Int, sql: String): Int =
            withCString(sql)(pSql => call("sqlite3_exec", db, pSql, 0, 0, 0).asInstanceOf[Int])

        /** `sqlite3_exec` without its callback. The message is left on the connection rather than returned, so
          * there is no allocation for a caller to free.
          */
        private val execSimple: js.Function2[Int, String, Int] = (db, sql) => execSimpleRaw(db, sql)

        /** Binds that COPY their argument. `SQLITE_TRANSIENT` is the only correct choice: the heap block below is
          * freed the moment the call returns.
          */
        private val bindTextCopy: js.Function4[Int, Int, Uint8Array, Int, Int] = (stmt, idx, value, nBytes) =>
            withBytes(value, nBytes)(ptr => call("sqlite3_bind_text", stmt, idx, ptr, nBytes, Transient).asInstanceOf[Int])

        private val bindBlobCopy: js.Function4[Int, Int, Uint8Array, Int, Int] = (stmt, idx, value, nBytes) =>
            withBytes(value, nBytes)(ptr => call("sqlite3_bind_blob", stmt, idx, ptr, nBytes, Transient).asInstanceOf[Int])

        /** Reads a column's bytes, answering its FULL length even when that exceeds `cap`.
          *
          * The length comes from `sqlite3_column_bytes` rather than from the pointer, because SQLite text may
          * contain NUL and a C-string read truncates at the first one. Text and blob are separate because
          * `column_bytes` reports the length of whichever representation was last requested, so the length has to
          * be read through the same accessor that produced the pointer.
          */
        private val columnTextBytes: js.Function4[Int, Int, Uint8Array, Int, Int] = (stmt, idx, dst, cap) =>
            val src = call("sqlite3_column_text", stmt, idx).asInstanceOf[Int]
            val n   = call("sqlite3_column_bytes", stmt, idx).asInstanceOf[Int]
            copyOut(src, n, dst, cap)
            n

        private val columnBlobBytes: js.Function4[Int, Int, Uint8Array, Int, Int] = (stmt, idx, dst, cap) =>
            val src = call("sqlite3_column_blob", stmt, idx).asInstanceOf[Int]
            val n   = call("sqlite3_column_bytes", stmt, idx).asInstanceOf[Int]
            copyOut(src, n, dst, cap)
            n

        /** The column's DECLARED type, with absent distinguished from empty by a -1 return.
          *
          * A column not traceable to a declared one (every expression, and every column declared with no type)
          * has no decltype, and the codec dispatches on this, so "absent" and "empty" must not arrive alike.
          */
        private val columnDecltypeBytes: js.Function4[Int, Int, Uint8Array, Int, Int] = (stmt, idx, dst, cap) =>
            val decl = call("sqlite3_column_decltype", stmt, idx).asInstanceOf[Int]
            if decl == 0 then -1
            else
                val h = heap
                var n = 0
                while (h.get(decl + n).toInt & 0xff) != 0 do n += 1
                copyOut(decl, n, dst, cap)
                n
            end if

        // --- Plain passthroughs, and the two pointer-returning reads ---

        private def int1(name: String): js.Function1[Int, Int]         = a => call(name, a).asInstanceOf[Int]
        private def int2(name: String): js.Function2[Int, Int, Int]    = (a, b) => call(name, a, b).asInstanceOf[Int]
        private def big1(name: String): js.Function1[Int, js.BigInt]   = a => call(name, a).asInstanceOf[js.BigInt]
        private def str2(name: String): js.Function2[Int, Int, String] = (a, b) => cstrToJs(call(name, a, b).asInstanceOf[Int])

        /** Every symbol a binding may name, keyed by the C symbol it declares. */
        val bySymbol: Map[String, js.Any] = Map(
            "kyo_sqlite3_open_v2"               -> openV2,
            "kyo_sqlite3_prepare_one"           -> prepareOne,
            "kyo_sqlite3_db_config_int"         -> dbConfigInt,
            "kyo_sqlite3_configure_connection"  -> configureConnection,
            "kyo_sqlite3_exec_simple"           -> execSimple,
            "kyo_sqlite3_bind_text_copy"        -> bindTextCopy,
            "kyo_sqlite3_bind_blob_copy"        -> bindBlobCopy,
            "kyo_sqlite3_column_text_bytes"     -> columnTextBytes,
            "kyo_sqlite3_column_blob_bytes"     -> columnBlobBytes,
            "kyo_sqlite3_column_decltype_bytes" -> columnDecltypeBytes,
            "sqlite3_close_v2"                  -> int1("sqlite3_close_v2"),
            "sqlite3_step"                      -> int1("sqlite3_step"),
            "sqlite3_finalize"                  -> int1("sqlite3_finalize"),
            "sqlite3_reset"                     -> int1("sqlite3_reset"),
            "sqlite3_clear_bindings"            -> int1("sqlite3_clear_bindings"),
            "sqlite3_bind_parameter_count"      -> int1("sqlite3_bind_parameter_count"),
            "sqlite3_bind_null"                 -> int2("sqlite3_bind_null"),
            "sqlite3_bind_int64"        -> ((s: Int, i: Int, v: js.BigInt) => call("sqlite3_bind_int64", s, i, v).asInstanceOf[Int]),
            "sqlite3_bind_double"       -> ((s: Int, i: Int, v: Double) => call("sqlite3_bind_double", s, i, v).asInstanceOf[Int]),
            "sqlite3_column_count"      -> int1("sqlite3_column_count"),
            "sqlite3_column_name"       -> str2("sqlite3_column_name"),
            "sqlite3_column_type"       -> int2("sqlite3_column_type"),
            "sqlite3_column_int64"      -> ((s: Int, i: Int) => call("sqlite3_column_int64", s, i).asInstanceOf[js.BigInt]),
            "sqlite3_column_double"     -> ((s: Int, i: Int) => call("sqlite3_column_double", s, i).asInstanceOf[Double]),
            "sqlite3_extended_errcode"  -> int1("sqlite3_extended_errcode"),
            "sqlite3_errmsg"            -> ((db: Int) => cstrToJs(call("sqlite3_errmsg", db).asInstanceOf[Int])),
            "sqlite3_changes64"         -> big1("sqlite3_changes64"),
            "sqlite3_last_insert_rowid" -> big1("sqlite3_last_insert_rowid"),
            "sqlite3_interrupt"         -> ((db: Int) => discard(call("sqlite3_interrupt", db))),
            "sqlite3_libversion_number" -> (() => call("sqlite3_libversion_number").asInstanceOf[Int])
        )

    end Calls

end SqliteWasmFacade
