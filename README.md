# okay-tiny

Algebraic effects for Java 21 in one file: [`Fx.java`](src/main/java/okay/tiny/Fx.java), 250 lines of code, no
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

## Install

```kotlin
repositories { maven("https://okay-effects.github.io/okay-tiny/maven") }
dependencies { implementation("io.github.okay-effects:okay-tiny:0.1.0") }
```

```xml
<repository><id>okay-tiny</id><url>https://okay-effects.github.io/okay-tiny/maven</url></repository>

<dependency>
  <groupId>io.github.okay-effects</groupId><artifactId>okay-tiny</artifactId><version>0.1.0</version>
</dependency>
```

Java 21, no dependency, the module `okay.tiny`. Or copy `Fx.java` into your code base: it is one file, made to
be read and owned. `Std.java` is a second, with the usual effects.

## The whole of it

| | |
|---|---|
| `Op<A, F>` | an operation: data; `A` is the type of its answer, `F` its effect |
| `Comp<A, R>` | a program: data; `A` is what it gives, `R` the effects it may ask — `<R extends Stock & Clock>` |
| `pure(a)`, `perform(op)`, `flatMap`, `map` | the ways to make one |
| `folding(Effect.class, state, ret, op -> switch (op) { .. })` | a handler: a fold of the part it is around |
| `on(op, (state, k) -> ..)` | a clause: `k.apply(answer, nextState)` goes on with the rest — once, twice, or never |
| `answer(op, value)` | the usual clause: answered where it is asked |
| `leave(value)` | a clause for any operation of the effect: the part is left, with the value |
| `handling(Effect.class, op -> switch (op) { .. })` | a handler with no state that gives what its part gives |
| `ensuring(part, after)` | `after` runs when the part ends: with a value, left by a clause, or by an exception |
| `Fx.<R, A>row().handler(h).handle(part)` | handlers around a part of a program; the row `R` is left |
| `Fx.<A>top().handler(h).handle(program)` | around the whole program: nothing is left, and `run` takes it |
| `run(program)`, `run(program, see)` | runs it; `see` is given every operation asked, as data, before it is answered |

What javac refuses: an operation the row does not have, an effect left without a handler, a handler that leaves
out an operation of its effect, an answer or a next state of another type, a handler that asks an effect that
does not stay around it. `src/test/negative` has a program for each, and `NegativeTest` checks that each is
refused for its own reason.

What a handler can do with the rest of its part, `k`: go on with it (an answer), leave it (the part gives the
handler's own value: an `Optional`, a failure), go on with it more than once (every way of a choice — the rest is
a value, never replayed). Its state is a value too: a rest gone on with twice has two.

## The usual effects

[`Std.java`](src/main/java/okay/tiny/Std.java), 212 lines beside the kernel and written only with what it gives:
each effect with the handler that is its monad.

| Effect | Asked with | Handler | The part gives |
|---|---|---|---|
| `State<S>` | `get()`, `put(s)`, `update(f)` | `state(initial)` | `Final<S, A>`: the last state and the value |
| `Fail<E>` | `fail(error)` | `option(E.class)`, `result(E.class)` | `Optional<A>`; `Result<E, A>` |
| `Emit<X>` | `tell(item)` | `told(X.class)` | `Told<X, A>`: what was told and the value |
| `Env<E>` | `ask()` | `env(value)` | `A` |
| `Choice` | `choose(options)` | `all()` | `List<A>`: every way |
| `Async` | `await(start)`, `awaitAll(starts)` | `blocking()`; or `start(program)` | `A`; a `CompletableFuture<A>` |

And the usual shapes of a program, so that a loop is not a recursion each time: `forEach`, `collect`, `fold`,
`iterate`, `when`, `zip`.

The order of two handlers is the order of two monads (`StdTest`): a failure inside a state keeps the state,
`Final<S, Result<E, A>>`; a state inside a failure is lost with the part, `Result<E, Final<S, A>>`.

## Documentation

| | |
|---|---|
| [The guide](docs/guide.md) | from nothing to an application, in order; every example is a test |
| [From ports and adapters](docs/ports-and-adapters.md) | a service with interfaces and injection, moved in four steps |
| [Asynchrony as an effect](docs/async.md) | one program, a handler that waits and one that does not |
| [Direct style](docs/direct.md) | a method written as plain Java with `$(program)`, rewritten into the chain by a plugin of javac |
| [In a Spring Boot service](docs/spring.md) | beans, a database, Spring's transactions, a controller — a sample that runs |
| [Reference](docs/reference.md) | every type and word, what javac refuses, what it costs |
| [The machine](docs/machine.md) | for reviewing `Fx.java` |
| [The design, and why](DESIGN.md) | the decisions, and what was tried and left |
| [For coding agents](AGENTS.md) | the model on one page |

## Build

```
./gradlew test                        # scenarios, the docs' examples, 24 refusals, the TCK; the plugin; Spring
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
