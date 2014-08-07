
//@skip-test (There is some issues with javari at the moment.)

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;


// TODO: casts

/** super # instMethod */
//SUPER(ReferenceMode.INVOKE, false),
class Super {

    Object func1 (Object o) { return o; }
    <T> T func2 (T o) { return o; }

    class Sub extends Super {
        void context() {
            Function<Object, Object> f1 = super::func1;
            Function<String, String> f2 = super::func2;
            Function<? extends String, ? extends String> f3 = super::<String>func2;
        }
    }
}
class SuperWithArg<U> {

    void func1 (U o) { }

    class Sub extends SuperWithArg<Number> {
        void context() {
            Consumer<Integer> f1 = super::func1;
        }
    }
}

/** Type # instMethod */
// UNBOUND(ReferenceMode.INVOKE, true),
class Unbound {
    <T> T func1 (T o) { return o; }

    void context() {
        Function<String, String> f1 = String::toString;
        BiFunction<Unbound, String, String> f2 = Unbound::func1;
        BiFunction<? extends Unbound, ? super Integer, ? extends Integer> f3 = Unbound::<Integer>func1;
    }
}
class UnboundWithArg<U> {
    void func1 (U u) {  }

    void context() {
        BiConsumer<UnboundWithArg<String>, String> f1 = UnboundWithArg::func1;
        BiConsumer<UnboundWithArg<String>, String> f2 = UnboundWithArg<String>::func1;
        BiConsumer<? extends UnboundWithArg<String>, String> f3 = UnboundWithArg::func1;
    }
}

/** Type # staticMethod */
// STATIC(ReferenceMode.INVOKE, false),
class Static {
    static <T> T func1 (T o) { return o; }
    void context() {
        Function<String, String> f1 = Static::func1;
        Function<String, String> f2 = Static::<String>func1;
    }
}

///** Expr # instMethod */
//// BOUND(ReferenceMode.INVOKE, false),
class Bound {
    <T> T func1 (T o) { return o; }
    void context(Bound bound) {
        Function<String, String> f1 = bound::func1;
        Function<String, String> f2 = this::func1;
        Function<String, String> f3 = this::<String>func1;
    }
}
class BoundWithArg<U> {
    void func1 (U param) { }
    void context(BoundWithArg<Number> bound) {
        Consumer<Number> f1 = bound::func1;
        Consumer<Integer> f2 = bound::func1;
    }
}

/** Inner # new */
// IMPLICIT_INNER(ReferenceMode.NEW, false),
class Outer {
    void context(Outer other) {
        Supplier<Inner> f1 = Inner::new;
        // I can't get this to compile. it is allowed some other way?
//        Supplier<Inner> f2 = other.Inner::new;
    }
    class Inner extends Outer {

    }
}
class OuterWithArg {
    void context() {
        Supplier<Inner<String>> f1 = Inner::new;
        Supplier<? extends Inner<? extends Number>> f2 = Inner<Integer>::new;
    }

    class Inner<T> extends OuterWithArg { }
}

/** Toplevel # new */
// TOPLEVEL(ReferenceMode.NEW, false),
class TopLevel {
    TopLevel() {}
    <T> TopLevel(T s) {}
    void context() {
        Supplier<TopLevel> f1 = TopLevel::new;
        Function<String, TopLevel> f2 = TopLevel::new;
        Function<String, TopLevel> f3 = TopLevel::<String>new;
    }
}
class TopLevelWithArg<T> {
    TopLevelWithArg() {}
    <U> TopLevelWithArg(U s) {}
    void context() {
        Supplier<TopLevelWithArg<String>> f1 = TopLevelWithArg::new;
        Supplier<TopLevelWithArg<String>> f2 = TopLevelWithArg<String>::new;
        Function<String, TopLevelWithArg<String>> f3 = TopLevelWithArg<String>::<String>new;
    }
}

/** ArrayType # new */
// ARRAY_CTOR(ReferenceMode.NEW, false);
class ArrayType {
    void context() {
        Function<Integer, String[]> string = String[]::new;
    }
}