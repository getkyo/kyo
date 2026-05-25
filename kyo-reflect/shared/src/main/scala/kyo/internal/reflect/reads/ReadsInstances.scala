package kyo.internal.reflect.reads

import kyo.*
import kyo.Reflect.*

/** Built-in Reflect.Reads instances for primitive types, Reflect domain types, and collection wrappers.
  *
  * These givens are exported from the Reflect.Reads companion object and are automatically in scope wherever Reflect.Reads is used.
  *
  * Package is kyo.internal.reflect.reads to match the sub-package convention for kyo-reflect internals.
  */
/** Companion object for direct import when needed. */
object ReadsInstances extends ReadsInstances

/** Mixin trait carrying all built-in Reflect.Reads given instances.
  *
  * Extended by `Reflect.Reads` companion so instances are automatically in scope via `summon[Reads[Reflect.Name]]` etc.
  */
trait ReadsInstances:

    // ── Reflect domain types ─────────────────────────────────────────────────

    given nameReads: Reads[Reflect.Name] = new Reads[Reflect.Name]:
        val symbolKinds   = Set(SymbolKind.values*)
        val needsBodies   = false
        val touchedFields = FieldSet.Name
        def read(sym: Symbol)(using Frame): Reflect.Name < (Sync & Abort[ReflectError]) =
            Kyo.lift(sym.name)

    given flagsReads: Reads[Reflect.Flags] = new Reads[Reflect.Flags]:
        val symbolKinds   = Set(SymbolKind.values*)
        val needsBodies   = false
        val touchedFields = FieldSet.Flags
        def read(sym: Symbol)(using Frame): Reflect.Flags < (Sync & Abort[ReflectError]) =
            Kyo.lift(sym.flags)

    given kindReads: Reads[Reflect.SymbolKind] = new Reads[Reflect.SymbolKind]:
        val symbolKinds   = Set(SymbolKind.values*)
        val needsBodies   = false
        val touchedFields = FieldSet.Kind
        def read(sym: Symbol)(using Frame): Reflect.SymbolKind < (Sync & Abort[ReflectError]) =
            Kyo.lift(sym.kind)

    given typeReads: Reads[Reflect.Type] = new Reads[Reflect.Type]:
        val symbolKinds   = Set(SymbolKind.values*)
        val needsBodies   = false
        val touchedFields = FieldSet.DeclaredType
        def read(sym: Symbol)(using Frame): Reflect.Type < (Sync & Abort[ReflectError]) =
            sym.declaredType

    given symbolReads: Reads[Reflect.Symbol] = new Reads[Reflect.Symbol]:
        val symbolKinds   = Set(SymbolKind.values*)
        val needsBodies   = false
        val touchedFields = FieldSet.Empty
        def read(sym: Symbol)(using Frame): Reflect.Symbol < (Sync & Abort[ReflectError]) =
            Kyo.lift(sym)

    // ── Primitives ───────────────────────────────────────────────────────────

    given booleanReads: Reads[Boolean] = new Reads[Boolean]:
        val symbolKinds   = Set(SymbolKind.values*)
        val needsBodies   = false
        val touchedFields = FieldSet.Empty
        def read(sym: Symbol)(using Frame): Boolean < (Sync & Abort[ReflectError]) =
            Kyo.lift(false)

    given intReads: Reads[Int] = new Reads[Int]:
        val symbolKinds   = Set(SymbolKind.values*)
        val needsBodies   = false
        val touchedFields = FieldSet.Empty
        def read(sym: Symbol)(using Frame): Int < (Sync & Abort[ReflectError]) =
            Kyo.lift(0)

    given longReads: Reads[Long] = new Reads[Long]:
        val symbolKinds   = Set(SymbolKind.values*)
        val needsBodies   = false
        val touchedFields = FieldSet.Empty
        def read(sym: Symbol)(using Frame): Long < (Sync & Abort[ReflectError]) =
            Kyo.lift(0L)

    given stringReads: Reads[String] = new Reads[String]:
        val symbolKinds   = Set(SymbolKind.values*)
        val needsBodies   = false
        val touchedFields = FieldSet.Name
        def read(sym: Symbol)(using Frame): String < (Sync & Abort[ReflectError]) =
            Kyo.lift(sym.name.asString)

    // ── Collection wrappers ──────────────────────────────────────────────────

    /** Reads a Chunk[T] by mapping over the symbol's declarations. Symbols not matching inner.symbolKinds are filtered out. */
    given chunkReads[T](using inner: Reads[T]): Reads[Chunk[T]] = new Reads[Chunk[T]]:
        val symbolKinds   = inner.symbolKinds
        val needsBodies   = inner.needsBodies
        val touchedFields = inner.touchedFields | FieldSet.Members
        def read(sym: Symbol)(using Frame): Chunk[T] < (Sync & Abort[ReflectError]) =
            sym.declarations.flatMap: decls =>
                Kyo.foreach(decls.filter(d => inner.symbolKinds.contains(d.kind)))(d => inner.read(d))

    /** Reads a Maybe[T] by reading the symbol's companion object (if present) via inner. */
    given maybeReads[T](using inner: Reads[T]): Reads[Maybe[T]] = new Reads[Maybe[T]]:
        val symbolKinds   = inner.symbolKinds
        val needsBodies   = inner.needsBodies
        val touchedFields = inner.touchedFields | FieldSet.Companion
        def read(sym: Symbol)(using Frame): Maybe[T] < (Sync & Abort[ReflectError]) =
            sym.companion.flatMap:
                case Absent        => Kyo.lift(Absent)
                case Present(csym) => inner.read(csym).map(Present(_))

    // ── Tuple Reads (arities 2-22) ────────────────────────────────────────────

    given tuple2Reads[A, B](using ra: Reads[A], rb: Reads[B]): Reads[(A, B)] = new Reads[(A, B)]:
        val symbolKinds   = ra.symbolKinds & rb.symbolKinds
        val needsBodies   = ra.needsBodies || rb.needsBodies
        val touchedFields = ra.touchedFields | rb.touchedFields
        def read(sym: Symbol)(using Frame): (A, B) < (Sync & Abort[ReflectError]) =
            for
                a <- ra.read(sym)
                b <- rb.read(sym)
            yield (a, b)

    given tuple3Reads[A, B, C](using ra: Reads[A], rb: Reads[B], rc: Reads[C]): Reads[(A, B, C)] =
        new Reads[(A, B, C)]:
            val symbolKinds   = ra.symbolKinds & rb.symbolKinds & rc.symbolKinds
            val needsBodies   = ra.needsBodies || rb.needsBodies || rc.needsBodies
            val touchedFields = ra.touchedFields | rb.touchedFields | rc.touchedFields
            def read(sym: Symbol)(using Frame): (A, B, C) < (Sync & Abort[ReflectError]) =
                for
                    a <- ra.read(sym)
                    b <- rb.read(sym)
                    c <- rc.read(sym)
                yield (a, b, c)

    given tuple4Reads[A, B, C, D](using ra: Reads[A], rb: Reads[B], rc: Reads[C], rd: Reads[D]): Reads[(A, B, C, D)] =
        new Reads[(A, B, C, D)]:
            val symbolKinds   = ra.symbolKinds & rb.symbolKinds & rc.symbolKinds & rd.symbolKinds
            val needsBodies   = ra.needsBodies || rb.needsBodies || rc.needsBodies || rd.needsBodies
            val touchedFields = ra.touchedFields | rb.touchedFields | rc.touchedFields | rd.touchedFields
            def read(sym: Symbol)(using Frame): (A, B, C, D) < (Sync & Abort[ReflectError]) =
                for
                    a <- ra.read(sym)
                    b <- rb.read(sym)
                    c <- rc.read(sym)
                    d <- rd.read(sym)
                yield (a, b, c, d)

    given tuple5Reads[A, B, C, D, E](
        using
        ra: Reads[A],
        rb: Reads[B],
        rc: Reads[C],
        rd: Reads[D],
        re: Reads[E]
    ): Reads[(A, B, C, D, E)] =
        new Reads[(A, B, C, D, E)]:
            val symbolKinds   = ra.symbolKinds & rb.symbolKinds & rc.symbolKinds & rd.symbolKinds & re.symbolKinds
            val needsBodies   = ra.needsBodies || rb.needsBodies || rc.needsBodies || rd.needsBodies || re.needsBodies
            val touchedFields = ra.touchedFields | rb.touchedFields | rc.touchedFields | rd.touchedFields | re.touchedFields
            def read(sym: Symbol)(using Frame): (A, B, C, D, E) < (Sync & Abort[ReflectError]) =
                for
                    a <- ra.read(sym)
                    b <- rb.read(sym)
                    c <- rc.read(sym)
                    d <- rd.read(sym)
                    e <- re.read(sym)
                yield (a, b, c, d, e)

    given tuple6Reads[A, B, C, D, E, F](
        using
        ra: Reads[A],
        rb: Reads[B],
        rc: Reads[C],
        rd: Reads[D],
        re: Reads[E],
        rf: Reads[F]
    ): Reads[(A, B, C, D, E, F)] =
        new Reads[(A, B, C, D, E, F)]:
            val symbolKinds =
                ra.symbolKinds & rb.symbolKinds & rc.symbolKinds & rd.symbolKinds & re.symbolKinds & rf.symbolKinds
            val needsBodies =
                ra.needsBodies || rb.needsBodies || rc.needsBodies || rd.needsBodies || re.needsBodies || rf.needsBodies
            val touchedFields =
                ra.touchedFields | rb.touchedFields | rc.touchedFields | rd.touchedFields | re.touchedFields | rf.touchedFields
            def read(sym: Symbol)(using Frame): (A, B, C, D, E, F) < (Sync & Abort[ReflectError]) =
                for
                    a <- ra.read(sym)
                    b <- rb.read(sym)
                    c <- rc.read(sym)
                    d <- rd.read(sym)
                    e <- re.read(sym)
                    f <- rf.read(sym)
                yield (a, b, c, d, e, f)

    given tuple7Reads[A, B, C, D, E, F, G](
        using
        ra: Reads[A],
        rb: Reads[B],
        rc: Reads[C],
        rd: Reads[D],
        re: Reads[E],
        rf: Reads[F],
        rg: Reads[G]
    ): Reads[(A, B, C, D, E, F, G)] =
        new Reads[(A, B, C, D, E, F, G)]:
            val symbolKinds =
                ra.symbolKinds & rb.symbolKinds & rc.symbolKinds & rd.symbolKinds & re.symbolKinds & rf.symbolKinds & rg.symbolKinds
            val needsBodies =
                ra.needsBodies || rb.needsBodies || rc.needsBodies || rd.needsBodies || re.needsBodies || rf.needsBodies || rg.needsBodies
            val touchedFields =
                ra.touchedFields | rb.touchedFields | rc.touchedFields | rd.touchedFields | re.touchedFields | rf.touchedFields | rg.touchedFields
            def read(sym: Symbol)(using Frame): (A, B, C, D, E, F, G) < (Sync & Abort[ReflectError]) =
                for
                    a <- ra.read(sym)
                    b <- rb.read(sym)
                    c <- rc.read(sym)
                    d <- rd.read(sym)
                    e <- re.read(sym)
                    f <- rf.read(sym)
                    g <- rg.read(sym)
                yield (a, b, c, d, e, f, g)

    given tuple8Reads[A, B, C, D, E, F, G, H](
        using
        ra: Reads[A],
        rb: Reads[B],
        rc: Reads[C],
        rd: Reads[D],
        re: Reads[E],
        rf: Reads[F],
        rg: Reads[G],
        rh: Reads[H]
    ): Reads[(A, B, C, D, E, F, G, H)] =
        new Reads[(A, B, C, D, E, F, G, H)]:
            val symbolKinds =
                ra.symbolKinds & rb.symbolKinds & rc.symbolKinds & rd.symbolKinds & re.symbolKinds & rf.symbolKinds & rg.symbolKinds & rh.symbolKinds
            val needsBodies =
                ra.needsBodies || rb.needsBodies || rc.needsBodies || rd.needsBodies || re.needsBodies || rf.needsBodies || rg.needsBodies || rh.needsBodies
            val touchedFields =
                ra.touchedFields | rb.touchedFields | rc.touchedFields | rd.touchedFields | re.touchedFields | rf.touchedFields | rg.touchedFields | rh.touchedFields
            def read(sym: Symbol)(using Frame): (A, B, C, D, E, F, G, H) < (Sync & Abort[ReflectError]) =
                for
                    a <- ra.read(sym)
                    b <- rb.read(sym)
                    c <- rc.read(sym)
                    d <- rd.read(sym)
                    e <- re.read(sym)
                    f <- rf.read(sym)
                    g <- rg.read(sym)
                    h <- rh.read(sym)
                yield (a, b, c, d, e, f, g, h)

    given tuple9Reads[A, B, C, D, E, F, G, H, I](
        using
        ra: Reads[A],
        rb: Reads[B],
        rc: Reads[C],
        rd: Reads[D],
        re: Reads[E],
        rf: Reads[F],
        rg: Reads[G],
        rh: Reads[H],
        ri: Reads[I]
    ): Reads[(A, B, C, D, E, F, G, H, I)] =
        new Reads[(A, B, C, D, E, F, G, H, I)]:
            val symbolKinds =
                ra.symbolKinds & rb.symbolKinds & rc.symbolKinds & rd.symbolKinds & re.symbolKinds & rf.symbolKinds & rg.symbolKinds & rh.symbolKinds & ri.symbolKinds
            val needsBodies =
                ra.needsBodies || rb.needsBodies || rc.needsBodies || rd.needsBodies || re.needsBodies || rf.needsBodies || rg.needsBodies || rh.needsBodies || ri.needsBodies
            val touchedFields =
                ra.touchedFields | rb.touchedFields | rc.touchedFields | rd.touchedFields | re.touchedFields | rf.touchedFields | rg.touchedFields | rh.touchedFields | ri.touchedFields
            def read(sym: Symbol)(using Frame): (A, B, C, D, E, F, G, H, I) < (Sync & Abort[ReflectError]) =
                for
                    a <- ra.read(sym)
                    b <- rb.read(sym)
                    c <- rc.read(sym)
                    d <- rd.read(sym)
                    e <- re.read(sym)
                    f <- rf.read(sym)
                    g <- rg.read(sym)
                    h <- rh.read(sym)
                    i <- ri.read(sym)
                yield (a, b, c, d, e, f, g, h, i)

    given tuple10Reads[A, B, C, D, E, F, G, H, I, J](
        using
        ra: Reads[A],
        rb: Reads[B],
        rc: Reads[C],
        rd: Reads[D],
        re: Reads[E],
        rf: Reads[F],
        rg: Reads[G],
        rh: Reads[H],
        ri: Reads[I],
        rj: Reads[J]
    ): Reads[(A, B, C, D, E, F, G, H, I, J)] =
        new Reads[(A, B, C, D, E, F, G, H, I, J)]:
            val symbolKinds =
                ra.symbolKinds & rb.symbolKinds & rc.symbolKinds & rd.symbolKinds & re.symbolKinds & rf.symbolKinds & rg.symbolKinds & rh.symbolKinds & ri.symbolKinds & rj.symbolKinds
            val needsBodies =
                ra.needsBodies || rb.needsBodies || rc.needsBodies || rd.needsBodies || re.needsBodies || rf.needsBodies || rg.needsBodies || rh.needsBodies || ri.needsBodies || rj.needsBodies
            val touchedFields =
                ra.touchedFields | rb.touchedFields | rc.touchedFields | rd.touchedFields | re.touchedFields | rf.touchedFields | rg.touchedFields | rh.touchedFields | ri.touchedFields | rj.touchedFields
            def read(sym: Symbol)(using Frame): (A, B, C, D, E, F, G, H, I, J) < (Sync & Abort[ReflectError]) =
                for
                    a <- ra.read(sym)
                    b <- rb.read(sym)
                    c <- rc.read(sym)
                    d <- rd.read(sym)
                    e <- re.read(sym)
                    f <- rf.read(sym)
                    g <- rg.read(sym)
                    h <- rh.read(sym)
                    i <- ri.read(sym)
                    j <- rj.read(sym)
                yield (a, b, c, d, e, f, g, h, i, j)

    given tuple11Reads[A, B, C, D, E, F, G, H, I, J, K](
        using
        ra: Reads[A],
        rb: Reads[B],
        rc: Reads[C],
        rd: Reads[D],
        re: Reads[E],
        rf: Reads[F],
        rg: Reads[G],
        rh: Reads[H],
        ri: Reads[I],
        rj: Reads[J],
        rk: Reads[K]
    ): Reads[(A, B, C, D, E, F, G, H, I, J, K)] =
        new Reads[(A, B, C, D, E, F, G, H, I, J, K)]:
            val symbolKinds =
                ra.symbolKinds & rb.symbolKinds & rc.symbolKinds & rd.symbolKinds & re.symbolKinds & rf.symbolKinds & rg.symbolKinds & rh.symbolKinds & ri.symbolKinds & rj.symbolKinds & rk.symbolKinds
            val needsBodies =
                ra.needsBodies || rb.needsBodies || rc.needsBodies || rd.needsBodies || re.needsBodies || rf.needsBodies || rg.needsBodies || rh.needsBodies || ri.needsBodies || rj.needsBodies || rk.needsBodies
            val touchedFields =
                ra.touchedFields | rb.touchedFields | rc.touchedFields | rd.touchedFields | re.touchedFields | rf.touchedFields | rg.touchedFields | rh.touchedFields | ri.touchedFields | rj.touchedFields | rk.touchedFields
            def read(sym: Symbol)(using Frame): (A, B, C, D, E, F, G, H, I, J, K) < (Sync & Abort[ReflectError]) =
                for
                    a <- ra.read(sym)
                    b <- rb.read(sym)
                    c <- rc.read(sym)
                    d <- rd.read(sym)
                    e <- re.read(sym)
                    f <- rf.read(sym)
                    g <- rg.read(sym)
                    h <- rh.read(sym)
                    i <- ri.read(sym)
                    j <- rj.read(sym)
                    k <- rk.read(sym)
                yield (a, b, c, d, e, f, g, h, i, j, k)

    given tuple12Reads[A, B, C, D, E, F, G, H, I, J, K, L](
        using
        ra: Reads[A],
        rb: Reads[B],
        rc: Reads[C],
        rd: Reads[D],
        re: Reads[E],
        rf: Reads[F],
        rg: Reads[G],
        rh: Reads[H],
        ri: Reads[I],
        rj: Reads[J],
        rk: Reads[K],
        rl: Reads[L]
    ): Reads[(A, B, C, D, E, F, G, H, I, J, K, L)] =
        new Reads[(A, B, C, D, E, F, G, H, I, J, K, L)]:
            val symbolKinds =
                ra.symbolKinds & rb.symbolKinds & rc.symbolKinds & rd.symbolKinds & re.symbolKinds & rf.symbolKinds & rg.symbolKinds & rh.symbolKinds & ri.symbolKinds & rj.symbolKinds & rk.symbolKinds & rl.symbolKinds
            val needsBodies =
                ra.needsBodies || rb.needsBodies || rc.needsBodies || rd.needsBodies || re.needsBodies || rf.needsBodies || rg.needsBodies || rh.needsBodies || ri.needsBodies || rj.needsBodies || rk.needsBodies || rl.needsBodies
            val touchedFields =
                ra.touchedFields | rb.touchedFields | rc.touchedFields | rd.touchedFields | re.touchedFields | rf.touchedFields | rg.touchedFields | rh.touchedFields | ri.touchedFields | rj.touchedFields | rk.touchedFields | rl.touchedFields
            def read(sym: Symbol)(using Frame): (A, B, C, D, E, F, G, H, I, J, K, L) < (Sync & Abort[ReflectError]) =
                for
                    a <- ra.read(sym)
                    b <- rb.read(sym)
                    c <- rc.read(sym)
                    d <- rd.read(sym)
                    e <- re.read(sym)
                    f <- rf.read(sym)
                    g <- rg.read(sym)
                    h <- rh.read(sym)
                    i <- ri.read(sym)
                    j <- rj.read(sym)
                    k <- rk.read(sym)
                    l <- rl.read(sym)
                yield (a, b, c, d, e, f, g, h, i, j, k, l)

    given tuple13Reads[A, B, C, D, E, F, G, H, I, J, K, L, M](
        using
        ra: Reads[A],
        rb: Reads[B],
        rc: Reads[C],
        rd: Reads[D],
        re: Reads[E],
        rf: Reads[F],
        rg: Reads[G],
        rh: Reads[H],
        ri: Reads[I],
        rj: Reads[J],
        rk: Reads[K],
        rl: Reads[L],
        rm: Reads[M]
    ): Reads[(A, B, C, D, E, F, G, H, I, J, K, L, M)] =
        new Reads[(A, B, C, D, E, F, G, H, I, J, K, L, M)]:
            val symbolKinds =
                ra.symbolKinds & rb.symbolKinds & rc.symbolKinds & rd.symbolKinds & re.symbolKinds & rf.symbolKinds & rg.symbolKinds & rh.symbolKinds & ri.symbolKinds & rj.symbolKinds & rk.symbolKinds & rl.symbolKinds & rm.symbolKinds
            val needsBodies =
                ra.needsBodies || rb.needsBodies || rc.needsBodies || rd.needsBodies || re.needsBodies || rf.needsBodies || rg.needsBodies || rh.needsBodies || ri.needsBodies || rj.needsBodies || rk.needsBodies || rl.needsBodies || rm.needsBodies
            val touchedFields =
                ra.touchedFields | rb.touchedFields | rc.touchedFields | rd.touchedFields | re.touchedFields | rf.touchedFields | rg.touchedFields | rh.touchedFields | ri.touchedFields | rj.touchedFields | rk.touchedFields | rl.touchedFields | rm.touchedFields
            def read(sym: Symbol)(using Frame): (A, B, C, D, E, F, G, H, I, J, K, L, M) < (Sync & Abort[ReflectError]) =
                for
                    a <- ra.read(sym)
                    b <- rb.read(sym)
                    c <- rc.read(sym)
                    d <- rd.read(sym)
                    e <- re.read(sym)
                    f <- rf.read(sym)
                    g <- rg.read(sym)
                    h <- rh.read(sym)
                    i <- ri.read(sym)
                    j <- rj.read(sym)
                    k <- rk.read(sym)
                    l <- rl.read(sym)
                    m <- rm.read(sym)
                yield (a, b, c, d, e, f, g, h, i, j, k, l, m)

    given tuple14Reads[A, B, C, D, E, F, G, H, I, J, K, L, M, N](
        using
        ra: Reads[A],
        rb: Reads[B],
        rc: Reads[C],
        rd: Reads[D],
        re: Reads[E],
        rf: Reads[F],
        rg: Reads[G],
        rh: Reads[H],
        ri: Reads[I],
        rj: Reads[J],
        rk: Reads[K],
        rl: Reads[L],
        rm: Reads[M],
        rn: Reads[N]
    ): Reads[(A, B, C, D, E, F, G, H, I, J, K, L, M, N)] =
        new Reads[(A, B, C, D, E, F, G, H, I, J, K, L, M, N)]:
            val symbolKinds =
                ra.symbolKinds & rb.symbolKinds & rc.symbolKinds & rd.symbolKinds & re.symbolKinds & rf.symbolKinds & rg.symbolKinds & rh.symbolKinds & ri.symbolKinds & rj.symbolKinds & rk.symbolKinds & rl.symbolKinds & rm.symbolKinds & rn.symbolKinds
            val needsBodies =
                ra.needsBodies || rb.needsBodies || rc.needsBodies || rd.needsBodies || re.needsBodies || rf.needsBodies || rg.needsBodies || rh.needsBodies || ri.needsBodies || rj.needsBodies || rk.needsBodies || rl.needsBodies || rm.needsBodies || rn.needsBodies
            val touchedFields =
                ra.touchedFields | rb.touchedFields | rc.touchedFields | rd.touchedFields | re.touchedFields | rf.touchedFields | rg.touchedFields | rh.touchedFields | ri.touchedFields | rj.touchedFields | rk.touchedFields | rl.touchedFields | rm.touchedFields | rn.touchedFields
            def read(sym: Symbol)(using Frame): (A, B, C, D, E, F, G, H, I, J, K, L, M, N) < (Sync & Abort[ReflectError]) =
                for
                    a <- ra.read(sym)
                    b <- rb.read(sym)
                    c <- rc.read(sym)
                    d <- rd.read(sym)
                    e <- re.read(sym)
                    f <- rf.read(sym)
                    g <- rg.read(sym)
                    h <- rh.read(sym)
                    i <- ri.read(sym)
                    j <- rj.read(sym)
                    k <- rk.read(sym)
                    l <- rl.read(sym)
                    m <- rm.read(sym)
                    n <- rn.read(sym)
                yield (a, b, c, d, e, f, g, h, i, j, k, l, m, n)

    given tuple15Reads[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O](
        using
        ra: Reads[A],
        rb: Reads[B],
        rc: Reads[C],
        rd: Reads[D],
        re: Reads[E],
        rf: Reads[F],
        rg: Reads[G],
        rh: Reads[H],
        ri: Reads[I],
        rj: Reads[J],
        rk: Reads[K],
        rl: Reads[L],
        rm: Reads[M],
        rn: Reads[N],
        ro: Reads[O]
    ): Reads[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O)] =
        new Reads[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O)]:
            val symbolKinds =
                ra.symbolKinds & rb.symbolKinds & rc.symbolKinds & rd.symbolKinds & re.symbolKinds & rf.symbolKinds & rg.symbolKinds & rh.symbolKinds & ri.symbolKinds & rj.symbolKinds & rk.symbolKinds & rl.symbolKinds & rm.symbolKinds & rn.symbolKinds & ro.symbolKinds
            val needsBodies =
                ra.needsBodies || rb.needsBodies || rc.needsBodies || rd.needsBodies || re.needsBodies || rf.needsBodies || rg.needsBodies || rh.needsBodies || ri.needsBodies || rj.needsBodies || rk.needsBodies || rl.needsBodies || rm.needsBodies || rn.needsBodies || ro.needsBodies
            val touchedFields =
                ra.touchedFields | rb.touchedFields | rc.touchedFields | rd.touchedFields | re.touchedFields | rf.touchedFields | rg.touchedFields | rh.touchedFields | ri.touchedFields | rj.touchedFields | rk.touchedFields | rl.touchedFields | rm.touchedFields | rn.touchedFields | ro.touchedFields
            def read(sym: Symbol)(using Frame): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) < (Sync & Abort[ReflectError]) =
                for
                    a <- ra.read(sym)
                    b <- rb.read(sym)
                    c <- rc.read(sym)
                    d <- rd.read(sym)
                    e <- re.read(sym)
                    f <- rf.read(sym)
                    g <- rg.read(sym)
                    h <- rh.read(sym)
                    i <- ri.read(sym)
                    j <- rj.read(sym)
                    k <- rk.read(sym)
                    l <- rl.read(sym)
                    m <- rm.read(sym)
                    n <- rn.read(sym)
                    o <- ro.read(sym)
                yield (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o)

    given tuple16Reads[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P](
        using
        ra: Reads[A],
        rb: Reads[B],
        rc: Reads[C],
        rd: Reads[D],
        re: Reads[E],
        rf: Reads[F],
        rg: Reads[G],
        rh: Reads[H],
        ri: Reads[I],
        rj: Reads[J],
        rk: Reads[K],
        rl: Reads[L],
        rm: Reads[M],
        rn: Reads[N],
        ro: Reads[O],
        rp: Reads[P]
    ): Reads[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P)] =
        new Reads[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P)]:
            val symbolKinds =
                ra.symbolKinds & rb.symbolKinds & rc.symbolKinds & rd.symbolKinds & re.symbolKinds & rf.symbolKinds & rg.symbolKinds & rh.symbolKinds & ri.symbolKinds & rj.symbolKinds & rk.symbolKinds & rl.symbolKinds & rm.symbolKinds & rn.symbolKinds & ro.symbolKinds & rp.symbolKinds
            val needsBodies =
                ra.needsBodies || rb.needsBodies || rc.needsBodies || rd.needsBodies || re.needsBodies || rf.needsBodies || rg.needsBodies || rh.needsBodies || ri.needsBodies || rj.needsBodies || rk.needsBodies || rl.needsBodies || rm.needsBodies || rn.needsBodies || ro.needsBodies || rp.needsBodies
            val touchedFields =
                ra.touchedFields | rb.touchedFields | rc.touchedFields | rd.touchedFields | re.touchedFields | rf.touchedFields | rg.touchedFields | rh.touchedFields | ri.touchedFields | rj.touchedFields | rk.touchedFields | rl.touchedFields | rm.touchedFields | rn.touchedFields | ro.touchedFields | rp.touchedFields
            def read(sym: Symbol)(using Frame): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) < (Sync & Abort[ReflectError]) =
                for
                    a <- ra.read(sym)
                    b <- rb.read(sym)
                    c <- rc.read(sym)
                    d <- rd.read(sym)
                    e <- re.read(sym)
                    f <- rf.read(sym)
                    g <- rg.read(sym)
                    h <- rh.read(sym)
                    i <- ri.read(sym)
                    j <- rj.read(sym)
                    k <- rk.read(sym)
                    l <- rl.read(sym)
                    m <- rm.read(sym)
                    n <- rn.read(sym)
                    o <- ro.read(sym)
                    p <- rp.read(sym)
                yield (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p)

    given tuple17Reads[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q](
        using
        ra: Reads[A],
        rb: Reads[B],
        rc: Reads[C],
        rd: Reads[D],
        re: Reads[E],
        rf: Reads[F],
        rg: Reads[G],
        rh: Reads[H],
        ri: Reads[I],
        rj: Reads[J],
        rk: Reads[K],
        rl: Reads[L],
        rm: Reads[M],
        rn: Reads[N],
        ro: Reads[O],
        rp: Reads[P],
        rq: Reads[Q]
    ): Reads[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q)] =
        new Reads[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q)]:
            val symbolKinds =
                ra.symbolKinds & rb.symbolKinds & rc.symbolKinds & rd.symbolKinds & re.symbolKinds & rf.symbolKinds & rg.symbolKinds & rh.symbolKinds & ri.symbolKinds & rj.symbolKinds & rk.symbolKinds & rl.symbolKinds & rm.symbolKinds & rn.symbolKinds & ro.symbolKinds & rp.symbolKinds & rq.symbolKinds
            val needsBodies =
                ra.needsBodies || rb.needsBodies || rc.needsBodies || rd.needsBodies || re.needsBodies || rf.needsBodies || rg.needsBodies || rh.needsBodies || ri.needsBodies || rj.needsBodies || rk.needsBodies || rl.needsBodies || rm.needsBodies || rn.needsBodies || ro.needsBodies || rp.needsBodies || rq.needsBodies
            val touchedFields =
                ra.touchedFields | rb.touchedFields | rc.touchedFields | rd.touchedFields | re.touchedFields | rf.touchedFields | rg.touchedFields | rh.touchedFields | ri.touchedFields | rj.touchedFields | rk.touchedFields | rl.touchedFields | rm.touchedFields | rn.touchedFields | ro.touchedFields | rp.touchedFields | rq.touchedFields
            def read(sym: Symbol)(using Frame): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) < (Sync & Abort[ReflectError]) =
                for
                    a <- ra.read(sym)
                    b <- rb.read(sym)
                    c <- rc.read(sym)
                    d <- rd.read(sym)
                    e <- re.read(sym)
                    f <- rf.read(sym)
                    g <- rg.read(sym)
                    h <- rh.read(sym)
                    i <- ri.read(sym)
                    j <- rj.read(sym)
                    k <- rk.read(sym)
                    l <- rl.read(sym)
                    m <- rm.read(sym)
                    n <- rn.read(sym)
                    o <- ro.read(sym)
                    p <- rp.read(sym)
                    q <- rq.read(sym)
                yield (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q)

    given tuple18Reads[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R](
        using
        ra: Reads[A],
        rb: Reads[B],
        rc: Reads[C],
        rd: Reads[D],
        re: Reads[E],
        rf: Reads[F],
        rg: Reads[G],
        rh: Reads[H],
        ri: Reads[I],
        rj: Reads[J],
        rk: Reads[K],
        rl: Reads[L],
        rm: Reads[M],
        rn: Reads[N],
        ro: Reads[O],
        rp: Reads[P],
        rq: Reads[Q],
        rr: Reads[R]
    ): Reads[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R)] =
        new Reads[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R)]:
            val symbolKinds =
                ra.symbolKinds & rb.symbolKinds & rc.symbolKinds & rd.symbolKinds & re.symbolKinds & rf.symbolKinds & rg.symbolKinds & rh.symbolKinds & ri.symbolKinds & rj.symbolKinds & rk.symbolKinds & rl.symbolKinds & rm.symbolKinds & rn.symbolKinds & ro.symbolKinds & rp.symbolKinds & rq.symbolKinds & rr.symbolKinds
            val needsBodies =
                ra.needsBodies || rb.needsBodies || rc.needsBodies || rd.needsBodies || re.needsBodies || rf.needsBodies || rg.needsBodies || rh.needsBodies || ri.needsBodies || rj.needsBodies || rk.needsBodies || rl.needsBodies || rm.needsBodies || rn.needsBodies || ro.needsBodies || rp.needsBodies || rq.needsBodies || rr.needsBodies
            val touchedFields =
                ra.touchedFields | rb.touchedFields | rc.touchedFields | rd.touchedFields | re.touchedFields | rf.touchedFields | rg.touchedFields | rh.touchedFields | ri.touchedFields | rj.touchedFields | rk.touchedFields | rl.touchedFields | rm.touchedFields | rn.touchedFields | ro.touchedFields | rp.touchedFields | rq.touchedFields | rr.touchedFields
            def read(sym: Symbol)(using Frame): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) < (Sync & Abort[ReflectError]) =
                for
                    a <- ra.read(sym)
                    b <- rb.read(sym)
                    c <- rc.read(sym)
                    d <- rd.read(sym)
                    e <- re.read(sym)
                    f <- rf.read(sym)
                    g <- rg.read(sym)
                    h <- rh.read(sym)
                    i <- ri.read(sym)
                    j <- rj.read(sym)
                    k <- rk.read(sym)
                    l <- rl.read(sym)
                    m <- rm.read(sym)
                    n <- rn.read(sym)
                    o <- ro.read(sym)
                    p <- rp.read(sym)
                    q <- rq.read(sym)
                    r <- rr.read(sym)
                yield (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r)

    given tuple19Reads[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S](
        using
        ra: Reads[A],
        rb: Reads[B],
        rc: Reads[C],
        rd: Reads[D],
        re: Reads[E],
        rf: Reads[F],
        rg: Reads[G],
        rh: Reads[H],
        ri: Reads[I],
        rj: Reads[J],
        rk: Reads[K],
        rl: Reads[L],
        rm: Reads[M],
        rn: Reads[N],
        ro: Reads[O],
        rp: Reads[P],
        rq: Reads[Q],
        rr: Reads[R],
        rs: Reads[S]
    ): Reads[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S)] =
        new Reads[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S)]:
            val symbolKinds =
                ra.symbolKinds & rb.symbolKinds & rc.symbolKinds & rd.symbolKinds & re.symbolKinds & rf.symbolKinds & rg.symbolKinds & rh.symbolKinds & ri.symbolKinds & rj.symbolKinds & rk.symbolKinds & rl.symbolKinds & rm.symbolKinds & rn.symbolKinds & ro.symbolKinds & rp.symbolKinds & rq.symbolKinds & rr.symbolKinds & rs.symbolKinds
            val needsBodies =
                ra.needsBodies || rb.needsBodies || rc.needsBodies || rd.needsBodies || re.needsBodies || rf.needsBodies || rg.needsBodies || rh.needsBodies || ri.needsBodies || rj.needsBodies || rk.needsBodies || rl.needsBodies || rm.needsBodies || rn.needsBodies || ro.needsBodies || rp.needsBodies || rq.needsBodies || rr.needsBodies || rs.needsBodies
            val touchedFields =
                ra.touchedFields | rb.touchedFields | rc.touchedFields | rd.touchedFields | re.touchedFields | rf.touchedFields | rg.touchedFields | rh.touchedFields | ri.touchedFields | rj.touchedFields | rk.touchedFields | rl.touchedFields | rm.touchedFields | rn.touchedFields | ro.touchedFields | rp.touchedFields | rq.touchedFields | rr.touchedFields | rs.touchedFields
            def read(sym: Symbol)(using Frame): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) < (Sync & Abort[ReflectError]) =
                for
                    a <- ra.read(sym)
                    b <- rb.read(sym)
                    c <- rc.read(sym)
                    d <- rd.read(sym)
                    e <- re.read(sym)
                    f <- rf.read(sym)
                    g <- rg.read(sym)
                    h <- rh.read(sym)
                    i <- ri.read(sym)
                    j <- rj.read(sym)
                    k <- rk.read(sym)
                    l <- rl.read(sym)
                    m <- rm.read(sym)
                    n <- rn.read(sym)
                    o <- ro.read(sym)
                    p <- rp.read(sym)
                    q <- rq.read(sym)
                    r <- rr.read(sym)
                    s <- rs.read(sym)
                yield (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s)

    given tuple20Reads[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T](
        using
        ra: Reads[A],
        rb: Reads[B],
        rc: Reads[C],
        rd: Reads[D],
        re: Reads[E],
        rf: Reads[F],
        rg: Reads[G],
        rh: Reads[H],
        ri: Reads[I],
        rj: Reads[J],
        rk: Reads[K],
        rl: Reads[L],
        rm: Reads[M],
        rn: Reads[N],
        ro: Reads[O],
        rp: Reads[P],
        rq: Reads[Q],
        rr: Reads[R],
        rs: Reads[S],
        rt: Reads[T]
    ): Reads[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T)] =
        new Reads[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T)]:
            val symbolKinds =
                ra.symbolKinds & rb.symbolKinds & rc.symbolKinds & rd.symbolKinds & re.symbolKinds & rf.symbolKinds & rg.symbolKinds & rh.symbolKinds & ri.symbolKinds & rj.symbolKinds & rk.symbolKinds & rl.symbolKinds & rm.symbolKinds & rn.symbolKinds & ro.symbolKinds & rp.symbolKinds & rq.symbolKinds & rr.symbolKinds & rs.symbolKinds & rt.symbolKinds
            val needsBodies =
                ra.needsBodies || rb.needsBodies || rc.needsBodies || rd.needsBodies || re.needsBodies || rf.needsBodies || rg.needsBodies || rh.needsBodies || ri.needsBodies || rj.needsBodies || rk.needsBodies || rl.needsBodies || rm.needsBodies || rn.needsBodies || ro.needsBodies || rp.needsBodies || rq.needsBodies || rr.needsBodies || rs.needsBodies || rt.needsBodies
            val touchedFields =
                ra.touchedFields | rb.touchedFields | rc.touchedFields | rd.touchedFields | re.touchedFields | rf.touchedFields | rg.touchedFields | rh.touchedFields | ri.touchedFields | rj.touchedFields | rk.touchedFields | rl.touchedFields | rm.touchedFields | rn.touchedFields | ro.touchedFields | rp.touchedFields | rq.touchedFields | rr.touchedFields | rs.touchedFields | rt.touchedFields
            def read(sym: Symbol)(using
                Frame
            ): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) < (Sync & Abort[ReflectError]) =
                for
                    a <- ra.read(sym)
                    b <- rb.read(sym)
                    c <- rc.read(sym)
                    d <- rd.read(sym)
                    e <- re.read(sym)
                    f <- rf.read(sym)
                    g <- rg.read(sym)
                    h <- rh.read(sym)
                    i <- ri.read(sym)
                    j <- rj.read(sym)
                    k <- rk.read(sym)
                    l <- rl.read(sym)
                    m <- rm.read(sym)
                    n <- rn.read(sym)
                    o <- ro.read(sym)
                    p <- rp.read(sym)
                    q <- rq.read(sym)
                    r <- rr.read(sym)
                    s <- rs.read(sym)
                    t <- rt.read(sym)
                yield (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t)

    given tuple21Reads[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U](
        using
        ra: Reads[A],
        rb: Reads[B],
        rc: Reads[C],
        rd: Reads[D],
        re: Reads[E],
        rf: Reads[F],
        rg: Reads[G],
        rh: Reads[H],
        ri: Reads[I],
        rj: Reads[J],
        rk: Reads[K],
        rl: Reads[L],
        rm: Reads[M],
        rn: Reads[N],
        ro: Reads[O],
        rp: Reads[P],
        rq: Reads[Q],
        rr: Reads[R],
        rs: Reads[S],
        rt: Reads[T],
        ru: Reads[U]
    ): Reads[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U)] =
        new Reads[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U)]:
            val symbolKinds =
                ra.symbolKinds & rb.symbolKinds & rc.symbolKinds & rd.symbolKinds & re.symbolKinds & rf.symbolKinds & rg.symbolKinds & rh.symbolKinds & ri.symbolKinds & rj.symbolKinds & rk.symbolKinds & rl.symbolKinds & rm.symbolKinds & rn.symbolKinds & ro.symbolKinds & rp.symbolKinds & rq.symbolKinds & rr.symbolKinds & rs.symbolKinds & rt.symbolKinds & ru.symbolKinds
            val needsBodies =
                ra.needsBodies || rb.needsBodies || rc.needsBodies || rd.needsBodies || re.needsBodies || rf.needsBodies || rg.needsBodies || rh.needsBodies || ri.needsBodies || rj.needsBodies || rk.needsBodies || rl.needsBodies || rm.needsBodies || rn.needsBodies || ro.needsBodies || rp.needsBodies || rq.needsBodies || rr.needsBodies || rs.needsBodies || rt.needsBodies || ru.needsBodies
            val touchedFields =
                ra.touchedFields | rb.touchedFields | rc.touchedFields | rd.touchedFields | re.touchedFields | rf.touchedFields | rg.touchedFields | rh.touchedFields | ri.touchedFields | rj.touchedFields | rk.touchedFields | rl.touchedFields | rm.touchedFields | rn.touchedFields | ro.touchedFields | rp.touchedFields | rq.touchedFields | rr.touchedFields | rs.touchedFields | rt.touchedFields | ru.touchedFields
            def read(sym: Symbol)(using
                Frame
            ): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) < (Sync & Abort[ReflectError]) =
                for
                    a <- ra.read(sym)
                    b <- rb.read(sym)
                    c <- rc.read(sym)
                    d <- rd.read(sym)
                    e <- re.read(sym)
                    f <- rf.read(sym)
                    g <- rg.read(sym)
                    h <- rh.read(sym)
                    i <- ri.read(sym)
                    j <- rj.read(sym)
                    k <- rk.read(sym)
                    l <- rl.read(sym)
                    m <- rm.read(sym)
                    n <- rn.read(sym)
                    o <- ro.read(sym)
                    p <- rp.read(sym)
                    q <- rq.read(sym)
                    r <- rr.read(sym)
                    s <- rs.read(sym)
                    t <- rt.read(sym)
                    u <- ru.read(sym)
                yield (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u)

    given tuple22Reads[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V](
        using
        ra: Reads[A],
        rb: Reads[B],
        rc: Reads[C],
        rd: Reads[D],
        re: Reads[E],
        rf: Reads[F],
        rg: Reads[G],
        rh: Reads[H],
        ri: Reads[I],
        rj: Reads[J],
        rk: Reads[K],
        rl: Reads[L],
        rm: Reads[M],
        rn: Reads[N],
        ro: Reads[O],
        rp: Reads[P],
        rq: Reads[Q],
        rr: Reads[R],
        rs: Reads[S],
        rt: Reads[T],
        ru: Reads[U],
        rv: Reads[V]
    ): Reads[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V)] =
        new Reads[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V)]:
            val symbolKinds =
                ra.symbolKinds & rb.symbolKinds & rc.symbolKinds & rd.symbolKinds & re.symbolKinds & rf.symbolKinds & rg.symbolKinds & rh.symbolKinds & ri.symbolKinds & rj.symbolKinds & rk.symbolKinds & rl.symbolKinds & rm.symbolKinds & rn.symbolKinds & ro.symbolKinds & rp.symbolKinds & rq.symbolKinds & rr.symbolKinds & rs.symbolKinds & rt.symbolKinds & ru.symbolKinds & rv.symbolKinds
            val needsBodies =
                ra.needsBodies || rb.needsBodies || rc.needsBodies || rd.needsBodies || re.needsBodies || rf.needsBodies || rg.needsBodies || rh.needsBodies || ri.needsBodies || rj.needsBodies || rk.needsBodies || rl.needsBodies || rm.needsBodies || rn.needsBodies || ro.needsBodies || rp.needsBodies || rq.needsBodies || rr.needsBodies || rs.needsBodies || rt.needsBodies || ru.needsBodies || rv.needsBodies
            val touchedFields =
                ra.touchedFields | rb.touchedFields | rc.touchedFields | rd.touchedFields | re.touchedFields | rf.touchedFields | rg.touchedFields | rh.touchedFields | ri.touchedFields | rj.touchedFields | rk.touchedFields | rl.touchedFields | rm.touchedFields | rn.touchedFields | ro.touchedFields | rp.touchedFields | rq.touchedFields | rr.touchedFields | rs.touchedFields | rt.touchedFields | ru.touchedFields | rv.touchedFields
            def read(sym: Symbol)(using
                Frame
            ): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) < (Sync & Abort[ReflectError]) =
                for
                    a <- ra.read(sym)
                    b <- rb.read(sym)
                    c <- rc.read(sym)
                    d <- rd.read(sym)
                    e <- re.read(sym)
                    f <- rf.read(sym)
                    g <- rg.read(sym)
                    h <- rh.read(sym)
                    i <- ri.read(sym)
                    j <- rj.read(sym)
                    k <- rk.read(sym)
                    l <- rl.read(sym)
                    m <- rm.read(sym)
                    n <- rn.read(sym)
                    o <- ro.read(sym)
                    p <- rp.read(sym)
                    q <- rq.read(sym)
                    r <- rr.read(sym)
                    s <- rs.read(sym)
                    t <- rt.read(sym)
                    u <- ru.read(sym)
                    v <- rv.read(sym)
                yield (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v)

end ReadsInstances
