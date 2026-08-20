// BareValue decompiled with CFR 0.152, against kyo-kernel2 working tree at 29a7822df5 + uncommitted changes.
//
// In this build: Arrow no longer extends Function1; settled values read through Nested.unnest;
// map's two deferral arms unified behind a var slot with Slot >: Int; map's arrow extends
// Arrow.TransformBase so the trait forwarders live in the base class.
//
// Source (kyo-compile-bench/fixtures-expansion/BareValue.scala):
//   package kyobench
//   
//   import kyo.*
//   import kyo.kernel.*
//   
//   /** The common shape: the lambda returns a bare value, so the implicit lift fires inside map's expansion and brings a CanLift summon with
//     * it. `Int` takes the primitive arm of lift's inline match.
//     */
//   object BareValue:
//   
//       sealed trait Ask extends ArrowEffect[[X] =>> Unit, [X] =>> Int]
//   
//       def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())
//   
//       def one: Int < Ask = ask.map(_ + 1)
//   
//   end BareValue

// ============================================================
// BareValue$$anon$1.class   2126 bytes
// ============================================================
package kyobench;

import java.io.Serializable;
import kyo.Arrow;
import kyo.Arrow$;
import kyo.Frame;
import kyo.Frame$package$;
import kyo.Tag;
import kyo.Tag$package$;
import kyo.kernel.internal.Kyo;
import kyobench.BareValue;

public static final class BareValue$.anon.1
extends Kyo.Suspend<?, ?, BareValue.Ask, Object, Object, Object> {
    public String frame() {
        Frame$package$ frame$package$;
        Frame.package.Frame$ frame$ = Frame.package.Frame$.MODULE$;
        Frame$package$ Frame$package$_this = frame$package$ = Frame$package$.MODULE$;
        return "1BareValue.scala:13:50|kyobench.BareValue$|ask|suspend|def ask: Int < Ask = ArrowEffect.suspend[Any]\ud83d\udccd(Tag[Ask], ())";
    }

    public Serializable tag() {
        Tag$package$ tag$package$;
        Tag$package$ Tag$package$_this = tag$package$ = Tag$package$.MODULE$;
        return Tag.package.Tag$.MODULE$.apply((Serializable)((Object)"*8:C:java.lang.Object:0:::4\n4:C:scala.Matchable:0:::5\n5:A\n0:C:kyobench.BareValue$.Ask:0:::1\n2:C:scala.Unit:0:::3\n7:C:kyo.kernel.Effect:0:::8\n3:C:scala.AnyVal:0:::4\n6:C:scala.Int:0:::3\n1:C:kyo.kernel.ArrowEffect:2:0:0:2:6:7"));
    }

    public void input() {
    }

    public Arrow cont() {
        return Arrow$.MODULE$.id();
    }
}

// ============================================================
// BareValue$$anon$2.class   1276 bytes
// ============================================================
package kyobench;

import kyo.Arrow;
import kyo.Frame;
import kyo.Frame$package$;
import kyobench.BareValue$;

public static final class BareValue$.anon.2
extends Arrow.TransformBase<Object, Object, Object> {
    public String frame() {
        Frame$package$ frame$package$;
        Frame.package.Frame$ frame$ = Frame.package.Frame$.MODULE$;
        Frame$package$ Frame$package$_this = frame$package$ = Frame$package$.MODULE$;
        return "1BareValue.scala:15:40|kyobench.BareValue$|one|map|def one: Int < Ask = ask.map(_ + 1)\ud83d\udccd";
    }

    public Object apply(Object v, Arrow next) {
        return BareValue$.MODULE$.kyobench$BareValue$$$_$run$1(v, next);
    }
}

// ============================================================
// BareValue$.class   2294 bytes
// ============================================================
package kyobench;

import java.io.Serializable;
import kyo.Arrow;
import kyo.Arrow$;
import kyo.Frame;
import kyo.Frame$package$;
import kyo.Tag;
import kyo.Tag$package$;
import kyo.kernel.Effect;
import kyo.kernel.Pending$package$;
import kyo.kernel.internal.Kyo;
import kyo.kernel.internal.Nested;
import kyo.kernel.internal.Safepoint;
import kyobench.BareValue;
import scala.runtime.BoxesRunTime;
import scala.runtime.ModuleSerializationProxy;

public final class BareValue$
implements Serializable {
    public static final BareValue$ MODULE$ = new BareValue$();

    private BareValue$() {
    }

    private Object writeReplace() {
        return new ModuleSerializationProxy(BareValue$.class);
    }

    public Object ask() {
        Pending$package$ pending$package$;
        Pending$package$ Pending$package$_this = pending$package$ = Pending$package$.MODULE$;
        return new Kyo.Suspend<?, ?, BareValue.Ask, Object, Object, Object>(){

            public String frame() {
                Frame$package$ frame$package$;
                Frame.package.Frame$ frame$ = Frame.package.Frame$.MODULE$;
                Frame$package$ Frame$package$_this = frame$package$ = Frame$package$.MODULE$;
                return "1BareValue.scala:13:50|kyobench.BareValue$|ask|suspend|def ask: Int < Ask = ArrowEffect.suspend[Any]\ud83d\udccd(Tag[Ask], ())";
            }

            public Serializable tag() {
                Tag$package$ tag$package$;
                Tag$package$ Tag$package$_this = tag$package$ = Tag$package$.MODULE$;
                return Tag.package.Tag$.MODULE$.apply((Serializable)((Object)"*8:C:java.lang.Object:0:::4\n4:C:scala.Matchable:0:::5\n5:A\n0:C:kyobench.BareValue$.Ask:0:::1\n2:C:scala.Unit:0:::3\n7:C:kyo.kernel.Effect:0:::8\n3:C:scala.AnyVal:0:::4\n6:C:scala.Int:0:::3\n1:C:kyo.kernel.ArrowEffect:2:0:0:2:6:7"));
            }

            public void input() {
            }

            public Arrow cont() {
                return Arrow$.MODULE$.id();
            }
        };
    }

    public Object one() {
        Pending$package$ pending$package$;
        Pending$package$ Pending$package$_this = pending$package$ = Pending$package$.MODULE$;
        return this.kyobench$BareValue$$$_$run$1(this.ask(), (Arrow)Arrow$.MODULE$.id());
    }

    private final Arrow arrow$1() {
        return new Arrow.TransformBase<Object, Object, Object>(){

            public String frame() {
                Frame$package$ frame$package$;
                Frame.package.Frame$ frame$ = Frame.package.Frame$.MODULE$;
                Frame$package$ Frame$package$_this = frame$package$ = Frame$package$.MODULE$;
                return "1BareValue.scala:15:40|kyobench.BareValue$|one|map|def one: Int < Ask = ask.map(_ + 1)\ud83d\udccd";
            }

            public Object apply(Object v, Arrow next) {
                return BareValue$.MODULE$.kyobench$BareValue$$$_$run$1(v, next);
            }
        };
    }

    public final Object kyobench$BareValue$$$_$run$1(Object v, Arrow next) {
        boolean shouldDefer;
        int slot = -1;
        boolean bl = shouldDefer = v instanceof Kyo || !Safepoint.enter((int)(slot = Safepoint.get()));
        if (shouldDefer) {
            return Effect.defer((Object)v, (Arrow)this.arrow$1(), (Arrow)next);
        }
        int n = BoxesRunTime.unboxToInt((Object)Nested.unnest((Object)v));
        int v$proxy1 = n + 1;
        Object out = next.head().apply((Object)BoxesRunTime.boxToInteger((int)v$proxy1), next.tail());
        Safepoint.exit((int)slot);
        return out;
    }
}

// ============================================================
// BareValue$Ask.class   229 bytes
// ============================================================
package kyobench;

public static interface BareValue.Ask {
}

// ============================================================
// BareValue.class   443 bytes
// ============================================================
package kyobench;

import kyobench.BareValue$;

public final class BareValue {
    public static Object ask() {
        return BareValue$.MODULE$.ask();
    }

    public static Object one() {
        return BareValue$.MODULE$.one();
    }

    public static interface Ask {
    }
}

