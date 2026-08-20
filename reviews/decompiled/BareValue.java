// BareValue, decompiled with CFR 0.152, against kyo-kernel2 at 876c72dbf9.
//
// Source (kyo-compile-bench/fixtures-expansion/BareValue.scala):
//
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
//
// The map expansion is arrow$1() plus run$1(); everything from compose() to
// apply$mcDD$sp() is Function1's specialization forwarders, inherited because Arrow
// extends (A => B < S), and emitted into every anonymous Transform in the program.

/*
 * Decompiled with CFR 0.152.
 */
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
import scala.Function1;
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
        return new Arrow.Transform<Object, Object, Object>(){

            public Function1 compose(Function1 g) {
                return Function1.compose$((Function1)this, (Function1)g);
            }

            public Function1 andThen(Function1 g) {
                return Function1.andThen$((Function1)this, (Function1)g);
            }

            public void apply$mcVI$sp(int x$0) {
                Function1.apply$mcVI$sp$((Function1)this, (int)x$0);
            }

            public void apply$mcVJ$sp(long x$0) {
                Function1.apply$mcVJ$sp$((Function1)this, (long)x$0);
            }

            public void apply$mcVF$sp(float x$0) {
                Function1.apply$mcVF$sp$((Function1)this, (float)x$0);
            }

            public void apply$mcVD$sp(double x$0) {
                Function1.apply$mcVD$sp$((Function1)this, (double)x$0);
            }

            public boolean apply$mcZI$sp(int x$0) {
                return Function1.apply$mcZI$sp$((Function1)this, (int)x$0);
            }

            public boolean apply$mcZJ$sp(long x$0) {
                return Function1.apply$mcZJ$sp$((Function1)this, (long)x$0);
            }

            public boolean apply$mcZF$sp(float x$0) {
                return Function1.apply$mcZF$sp$((Function1)this, (float)x$0);
            }

            public boolean apply$mcZD$sp(double x$0) {
                return Function1.apply$mcZD$sp$((Function1)this, (double)x$0);
            }

            public int apply$mcII$sp(int x$0) {
                return Function1.apply$mcII$sp$((Function1)this, (int)x$0);
            }

            public int apply$mcIJ$sp(long x$0) {
                return Function1.apply$mcIJ$sp$((Function1)this, (long)x$0);
            }

            public int apply$mcIF$sp(float x$0) {
                return Function1.apply$mcIF$sp$((Function1)this, (float)x$0);
            }

            public int apply$mcID$sp(double x$0) {
                return Function1.apply$mcID$sp$((Function1)this, (double)x$0);
            }

            public float apply$mcFI$sp(int x$0) {
                return Function1.apply$mcFI$sp$((Function1)this, (int)x$0);
            }

            public float apply$mcFJ$sp(long x$0) {
                return Function1.apply$mcFJ$sp$((Function1)this, (long)x$0);
            }

            public float apply$mcFF$sp(float x$0) {
                return Function1.apply$mcFF$sp$((Function1)this, (float)x$0);
            }

            public float apply$mcFD$sp(double x$0) {
                return Function1.apply$mcFD$sp$((Function1)this, (double)x$0);
            }

            public long apply$mcJI$sp(int x$0) {
                return Function1.apply$mcJI$sp$((Function1)this, (int)x$0);
            }

            public long apply$mcJJ$sp(long x$0) {
                return Function1.apply$mcJJ$sp$((Function1)this, (long)x$0);
            }

            public long apply$mcJF$sp(float x$0) {
                return Function1.apply$mcJF$sp$((Function1)this, (float)x$0);
            }

            public long apply$mcJD$sp(double x$0) {
                return Function1.apply$mcJD$sp$((Function1)this, (double)x$0);
            }

            public double apply$mcDI$sp(int x$0) {
                return Function1.apply$mcDI$sp$((Function1)this, (int)x$0);
            }

            public double apply$mcDJ$sp(long x$0) {
                return Function1.apply$mcDJ$sp$((Function1)this, (long)x$0);
            }

            public double apply$mcDF$sp(float x$0) {
                return Function1.apply$mcDF$sp$((Function1)this, (float)x$0);
            }

            public double apply$mcDD$sp(double x$0) {
                return Function1.apply$mcDD$sp$((Function1)this, (double)x$0);
            }

            public final Arrow chain(Arrow f) {
                return Arrow.chain$((Arrow)this, (Arrow)f);
            }

            public Arrow head() {
                return Arrow.Transform.head$((Arrow.Transform)this);
            }

            public Arrow tail() {
                return Arrow.Transform.tail$((Arrow.Transform)this);
            }

            public Object apply(Object v) {
                return Arrow.Transform.apply$((Arrow.Transform)this, (Object)v);
            }

            public String toString() {
                return Arrow.Transform.toString$((Arrow.Transform)this);
            }

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
        Object object = v;
        if (object instanceof Kyo) {
            Kyo kyo = (Kyo)object;
            return Effect.defer((Object)kyo, (Arrow)this.arrow$1(), (Arrow)next);
        }
        int slot = Safepoint.get();
        if (!Safepoint.enter((int)slot)) {
            return Effect.defer((Object)v, (Arrow)this.arrow$1(), (Arrow)next);
        }
        int n = BoxesRunTime.unboxToInt((Object)Nested.unnest((Object)v));
        int v$proxy1 = n + 1;
        Object out = next.head().apply((Object)BoxesRunTime.boxToInteger((int)v$proxy1), next.tail());
        Safepoint.exit((int)slot);
        return out;
    }
}
