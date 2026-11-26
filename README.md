# okay-tiny

Algebraic effects for Java 21 in one file: [`Fx.java`](src/main/java/okay/tiny/Fx.java), 180 lines of code, no
dependency, no threads, no reflection, no bytecode tricks. It is meant to be read in an hour and copied into a code
base that will not take a library.

A program asks operations and does nothing else to the world. A handler says what each operation means. The
program's type says what it may ask, and javac refuses a program whose effects are not all handled.

```java
import static okay.tiny.Fx.*;

// an effect: a sealed interface; each operation a record that says the type of its answer and its effect
sealed interface Stock {
  record Reserve(String sku, int qty) implements Stock, Op<Boolean, Stock> {}
}
sealed interface Refusal {
  record Invalid(String field, String why) implements Refusal, Op<Void, Refusal> {}
}

// a program: its type says what it gives and what it may ask
static <R extends Stock & Refusal> Comp<Order, R> place(Cart cart) {
  return perform(new Stock.Reserve(cart.sku(), cart.qty())).flatMap(ok ->
      ok ? pure(new Order(cart))
          : perform(new Refusal.Invalid(cart.sku(), "out of stock")).map(x -> new Order(cart)));
}

// a handler that only answers
static <B, R> Handler<Stock, B, B, R> stock(Map<String, Integer> left) {
  return handling(Stock.class, op -> switch (op) {
    case Stock.Reserve r -> answer(r, left.getOrDefault(r.sku(), 0) >= r.qty());
  });
}

// a handler is a fold: a state, what it gives where the part ends, and a clause for each operation
static <A, R> Handler<Refusal, A, Optional<A>, R> trying() {
  return folding(Refusal.class, null, (s, a) -> Optional.of(a), op -> switch (op) {
    case Refusal.Invalid i -> on(i, (s, k) -> pure(Optional.empty()));       // the rest is left
  });
}

// the handlers are put once, at the entry point
Optional<Order> o = run(Fx.<Order>top().handler(trying()).handler(stock(left)).handle(place(cart)));
```

## The whole of it

| | |
|---|---|
| `Op<A, F>` | an operation: data; `A` is the type of its answer, `F` its effect |
| `Comp<A, R>` | a program: data; `A` is what it gives, `R` the effects it may ask — `<R extends Stock & Clock>` |
| `pure(a)`, `perform(op)`, `flatMap`, `map` | the ways to make one |
| `folding(Effect.class, state, ret, op -> switch (op) { .. })` | a handler: a fold of the part it is around |
| `on(op, (state, k) -> ..)` | a clause: `k.apply(answer, nextState)` goes on with the rest — once, twice, or never |
| `answer(op, value)` | the usual clause: answered where it is asked |
| `handling(Effect.class, op -> switch (op) { .. })` | a handler with no state that gives what its part gives |
| `Fx.<R, A>row().handler(h).handle(part)` | handlers around a part of a program; the row `R` is left |
| `Fx.<A>top().handler(h).handle(program)` | around the whole program: nothing is left, and `run` takes it |

What javac refuses: an operation the row does not have, an effect left without a handler, a handler that leaves
out an operation of its effect, an answer or a next state of another type, a handler that asks an effect that
does not stay around it. `src/test/negative` has a program for each, and `NegativeTest` checks that each is
refused for its own reason.

What a handler can do with the rest of its part, `k`: go on with it (an answer), leave it (the part gives the
handler's own value: an `Optional`, a failure), go on with it more than once (every way of a choice — the rest is
a value, never replayed). Its state is a value too: a rest gone on with twice has two.

## Build

```
./gradlew test                        # 17 scenarios, a real flow of seven effects, 22 programs javac must refuse
./gradlew jmh                         # ns an operation (JMH)
./gradlew jmh -Pbench=StateBench.counter
```

Java 21. The tests run with `-Xss256k -Xmx64m`: the machine is a loop, and a program of a million steps takes
neither stack nor heap.

## Why it is as it is

[DESIGN.md](DESIGN.md): the decisions, the facts of Java that shaped them, what was tried and left, and the cost
of each operation.

## Its place

okay-tiny is a branch of the [okay-effects](https://github.com/okay-effects) family, apart from its canon: the
same idea — operations as data, handlers that answer them, the row in the type — in the least form that Java 21
can check. [okay-java](https://github.com/okay-effects/okay-java) is the full library (Java 25: rows of places,
direct style, sets of operations, sensitive operations under audit).

Apache License 2.0.
