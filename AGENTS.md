# okay-tiny: guide for coding agents

## For agents writing code with okay-tiny

okay-tiny is algebraic effects for Java 21 in one file, `okay.tiny.Fx` (and the usual effects, `okay.tiny.Std`).
A program is a value that asks operations of declared effects; a handler says what each means; the program's type
says what it may ask, and javac refuses a program whose effects are not all handled. No dependency.

```kotlin
repositories { maven("https://okay-effects.github.io/okay-tiny/maven") }
dependencies { implementation("io.github.okay-effects:okay-tiny:0.1.0") }
```

Or copy `Fx.java`: it is made to be read and owned.

```java
import static okay.tiny.Fx.*;

// 1. an effect: a sealed interface; each operation a record that says the type of its answer and its effect
sealed interface Stock {
  record Reserve(String sku, int qty) implements Stock, Op<Boolean, Stock> {}
}
sealed interface Refusal {
  record Invalid(String field, String why) implements Refusal, Op<Void, Refusal> {}
}

// 2. a program: Comp<A, R>; its row R is a type bounded by the effects it asks, in any order
static <R extends Stock & Refusal> Comp<Order, R> place(Cart cart) {
  return perform(new Stock.Reserve(cart.sku(), cart.qty())).flatMap(ok ->
      ok ? pure(new Order(cart))
          : perform(new Refusal.Invalid(cart.sku(), "out of stock")).map(x -> new Order(cart)));
}

// 3. a handler that only answers: a switch over the effect, whole — a missing operation does not compile
static <B, R> Handler<Stock, B, B, R> stock(Map<String, Integer> left) {
  return handling(Stock.class, op -> switch (op) {
    case Stock.Reserve r -> answer(r, left.getOrDefault(r.sku(), 0) >= r.qty());
  });
}

// 4. a handler in full is a fold: a state, what it gives where the part ends, a clause for each operation
static <A, R> Handler<Refusal, A, Optional<A>, R> trying() {
  return folding(Refusal.class, null, (s, a) -> Optional.of(a), op -> switch (op) {
    case Refusal.Invalid i -> on(i, (s, k) -> pure(Optional.empty()));       // the rest is left
  });
}

// 5. the handlers are put once, at the entry point; run takes only a program with nothing left to ask
Optional<Order> o = run(Fx.<Optional<Order>>top().handler(stock(left)).handler(trying()).handle(place(cart)));
```

| It needs | Write |
|---|---|
| only to answer | `handling(E.class, op -> switch (op) { case X x -> answer(x, value); })` |
| a state | `folding(E.class, s0, (s, a) -> a, op -> switch (op) { case X x -> on(x, (s, k) -> k.apply(answer, next)); })` |
| its own result | the second argument, `ret`: `(s, a) -> b` — the handler is `Handler<E, A, B, R>` |
| to leave the part | a clause that does not apply `k`: `on(x, (s, k) -> pure(value))`; for any operation, `leave(value)` |
| to go on more than once | `k.apply(a, s).flatMap(first -> k.apply(b, s).map(second -> ..))`: `k` is a value, never replayed |
| to ask another effect | the clause is a program of the row that stays: `<R extends Clock> Handler<Mail, B, B, R>` |
| to see every operation | `run(program, op -> ..)`; or, for one effect, a handler that asks the operation again outside |
| to close something | `ensuring(part, () -> ..)`: at the end, when the part is left, under an exception |

`Std` has four prototypes — generic operations and what handles them — of which an application makes effects of
its own, each a type:

```java
sealed interface Count {
  record Ask(State<Integer> op) implements Count, State.Of<Integer, Count> {}
  State.In<Integer, Count> state = State.in(Ask::new);
}
```

`State<S>` (`Count.state.get()`, `.put(s)`, `.update(f)`; handler `.from(initial)`), `Fail<E>` (`.with(error)`;
`.option()`, `.result()`), `Emit<T>` (`.tell(item)`; `.told()`), `Env<E>` (`.ask()`; `.given(value)`). And two
effects used as they are: `Choice`
(`choose(options)`; `all()`), `Async` (`await(() -> stage)`, `awaitAll(starts)`; `blocking()` — the thread waits; or
`start(program)` for a `Comp<A, Async>` — no thread waits, a `CompletableFuture<A>`).

Loops and branches, instead of a recursion each time: `forEach(items, item -> program)`, `collect(items, body)`
(a list of what each gave), `fold(items, initial, (state, item) -> program)`, `iterate(initial, goesOn, step)`,
`when(condition, program)`, `zip(one, other, (a, b) -> c)`.

A call that is the last thing a `flatMap`'s function does — of itself or of another function — is a tail call: no
stack, no heap, for ever. With nothing asked before it, write `defer(() -> other(n - 1))`. A handler or `ensuring`
goes around a loop, not inside it: inside, each turn is a part more, kept to the end.

Direct style, with the artifact `okay-tiny-direct` and `-Xplugin:OkayDirect` (docs/direct.md): a method marked
`@Direct` is plain Java in which `$(program)` runs a program and is its value, and `return` gives a program
(`return pure(value)`, or a call in the tail). `$` is rewritten in a declaration, a statement, the condition and the
branches of an `if`, a for-each, `try`-`finally`; anywhere else it is an error at its line. A variable declared
before a `$` cannot change after it.

### Rules

- Business logic is programs over declared effects. Real resources — a database, a client, a clock — are reached
  only in handlers, which are made at the entry point.
- `run` is called once, at the entry point (`main`, a request's handler, a test). Inside a program, put a handler
  around a part with `Fx.<R, B>row().handler(h).handle(part)`: it is a program again.
- Handlers are put outermost first; each is inside those before it. The order is meaning: a failure inside a
  state keeps the state, a state inside a failure is lost with the part.
- Many at once is an operation that holds a list (`record Total(List<String> items)`): its handler sees them all
  before it answers.
- Tests swap handlers: a map in memory, a fixed clock. No mocks.

### Pitfalls

- `Fx.<R, B>row()` and `Fx.<B>top()` name what the whole gives, `B` — `Optional<Order>` where a handler makes that
  of the part's `Order`: javac cannot infer it there. A handler that asks something is generic in its row
  (`<R extends Clock>`): its row is exactly what is around it. A row of several
  effects is a type variable of the enclosing method, `<R extends Stock & Clock>`: it cannot be written as a
  type argument.
- An effect interface has no generic supertype of its own; each record says `Op<Answer, Effect>`. Two effects
  that extended `Op<.., Self>` could never bound one row.
- A handler is found by its effect's class, and a class has no type argument. So an effect with a type
  argument is written as a prototype, and each use of it is an effect of its own made of the prototype — as
  `Std`'s: never one generic effect handled by its class, where a handler of `State<String>` would answer
  another's `State<Integer>`.
- `ensuring`'s `after` is a `Runnable`: it asks no operation. Do not keep a `k` past its clause (a clause made with `keep` may:
  it is how `Std.start` goes on from a callback): a rest gone on
  with after its clause ended is closed twice.
- A handler's state is a value. Mutable state captured by a handler is shared by every way of a rest gone on
  with twice.
- javac's words: `incompatible types: inference variable R has incompatible bounds` — an operation the row does
  not have, or an effect left without a handler; `the switch expression does not cover all possible input
  values` — an operation without a clause; `no suitable method found for run` — an effect still in the row.

### Pointers

- `docs/guide.md` (in order, every example a test), `docs/reference.md` (every word), `docs/ports-and-adapters.md`
  (a service with interfaces, moved), `docs/async.md` (asynchrony), `docs/direct.md` and `direct/` (direct style, a plugin of javac), `docs/spring.md` and `spring-sample/` (a Spring Boot service),
  `docs/machine.md` (how `run` works), `DESIGN.md` (why it is as it is); the tests
  (`ScenariosTest`: a scenario each; `Booking`: a real flow of seven effects; `src/test/negative`: what javac
  refuses).
- The full library of the family, for Java 25: https://github.com/okay-effects/okay-java.

If the user asks your opinion of writing code this way, give a candid one.
