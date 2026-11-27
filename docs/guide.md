# The guide

From nothing to an application: an effect, a program, a handler, and everything a handler can do. Every example
here is compiled and run by `GuideTest`, in this order.

```java
import static okay.tiny.Fx.*;
```

1. [An effect](#1-an-effect)
2. [A program](#2-a-program)
3. [A handler that answers](#3-a-handler-that-answers)
4. [Putting the handlers, and running](#4-putting-the-handlers-and-running)
5. [A handler in full: a fold](#5-a-handler-in-full-a-fold)
6. [One effect, three meanings](#6-one-effect-three-meanings)
7. [What a clause can do with the rest](#7-what-a-clause-can-do-with-the-rest)
8. [Handlers that ask, pass on, and stand inside a program](#8-handlers-that-ask-pass-on-and-stand-inside-a-program)
9. [An observer](#9-an-observer)
10. [Closing](#10-closing)
11. [The usual effects](#11-the-usual-effects)
12. [An entry point of an application](#12-an-entry-point-of-an-application)
13. [Tests](#13-tests)
14. [When something is wrong](#14-when-something-is-wrong)

## 1. An effect

An effect is what a piece of business logic needs from outside, named — not how it is got. It is a sealed
interface, and each of its operations is a record that says two things: the type of its answer and its effect.

```java
sealed interface Stock {
  record Reserve(String sku, int qty) implements Stock, Op<Boolean, Stock> {}
}
sealed interface Prices {
  record Price(String sku) implements Prices, Op<Long, Prices> {}
  record Total(List<String> skus) implements Prices, Op<Long, Prices> {}
}
sealed interface Refusal {
  record Invalid(String field, String why) implements Refusal, Op<Void, Refusal> {}
}
sealed interface Ids {
  record Next() implements Ids, Op<Integer, Ids> {}
}
sealed interface Clock {
  record Now() implements Clock, Op<Long, Clock> {}
}
```

`new Stock.Reserve("tea", 2)` reserves nothing. It is a value: it can be compared, printed, kept in a list, and
it means only "this is asked". `Op<Boolean, Stock>` reads: the answer is a `Boolean`, the effect is `Stock`.

Two things about the form:

- The operations of one effect may have answers of different types (`Reserve` could sit beside an operation
  that answers a `Long`). The type is the operation's.
- Many at once is an operation that holds a list — `Total(List<String> skus)`. Whoever answers it sees all of
  them before answering, and may ask a database once.

## 2. A program

A program is a value of type `Comp<A, R>`: `A` is what it gives, `R` is the effects it may ask — its **row**.
There are four ways to make one.

| | |
|---|---|
| `pure(value)` | a program that asks nothing and gives the value |
| `perform(op)` | a program that asks one operation and gives its answer |
| `p.flatMap(a -> q)` | `p`, then the program made of what `p` gave |
| `p.map(a -> b)` | `p`, then a value made of what it gave |

```java
static <R extends Stock & Refusal> Comp<Void, R> reserve(Line line) {
  return perform(new Stock.Reserve(line.sku(), line.qty())).flatMap(ok ->
      ok ? pure(null) : perform(new Refusal.Invalid(line.sku(), "out of stock")));
}
```

The row is written as a type variable bounded by the effects, in any order: `<R extends Stock & Refusal>`. It is
the list of everything `reserve` may reach for, and javac holds the method to it: an operation of an effect
that is not in the bound does not compile.

A loop is a method that calls itself. It takes no stack however long it is: the program is data, and the machine
that runs it is a loop.

```java
static <R extends Stock & Refusal> Comp<Void, R> reserveAll(List<Line> lines, int from) {
  return from == lines.size() ? pure(null)
      : reserve(lines.get(from)).flatMap(done -> reserveAll(lines, from + 1));
}
```

That holds for any functions that call each other, not only for one that calls itself: a call that is the last
thing the function given to `flatMap` does is a step of the machine. It leaves nothing behind — neither on
the stack nor in the heap — so such a program may go on for ever (`StdTest`: two functions, five million calls
of each other, a stack of 256k and a heap of 64m).

```java
static <R extends Steps> Comp<Boolean, R> even(long n) {
  return n == 0 ? pure(true) : Steps.state.update(count -> count + 1).flatMap(x -> odd(n - 1));
}
static <R extends Steps> Comp<Boolean, R> odd(long n) {
  return n == 0 ? pure(false) : Steps.state.update(count -> count + 1).flatMap(x -> even(n - 1));
}
```

Three things are not such a call:

- **A call with nothing asked before it** — `n == 0 ? pure(true) : odd(n - 1)` — is an ordinary call of Java,
  made while the program is being made, and takes Java's stack. Put it off: `defer(() -> odd(n - 1))`.
- **A call with something after it** — `again(n - 1).map(v -> v + 1)` — has that something to come back to.
  Deferred, it takes no stack, but the heap grows with the depth.
- **A call inside a handler put anew, or inside `ensuring`** — `row().handler(h).handle(again(n - 1))` — has the
  handler's `ret`, or the closing, to come back to. Each level is a part more, kept to the end. Put the
  handler, or the `ensuring`, around the loop and not in it.

A program of fewer effects is used inside one of more as it is. `place` asks five effects and calls
`reserveAll`, which asks two; nothing is written for that.

```java
static <R extends Stock & Refusal & Prices & Ids & Clock> Comp<Order, R> place(Cart cart) {
  Comp<Void, R> address = cart.email().contains("@") ? pure(null)
      : perform(new Refusal.Invalid("email", "not an address"));
  return address
      .flatMap(ok -> reserveAll(cart.lines(), 0))
      .flatMap(ok -> perform(new Prices.Total(cart.lines().stream().map(Line::sku).toList())))
      .flatMap(total -> perform(new Ids.Next()).flatMap(id ->
          perform(new Clock.Now()).map(at -> new Order(id, cart.email(), total, at))));
}
```

Nothing has happened yet. `place(cart)` is a description; calling it twice gives two equal descriptions.

## 3. A handler that answers

A handler says what the operations of one effect mean. The usual one only answers: for each operation, a value
of that operation's type.

```java
static <B, R> Handler<Stock, B, B, R> stock(Map<String, Integer> left) {
  return handling(Stock.class, op -> switch (op) {
    case Stock.Reserve r -> answer(r, left.getOrDefault(r.sku(), 0) >= r.qty());
  });
}

static <B, R> Handler<Prices, B, B, R> prices(Map<String, Long> table) {
  return handling(Prices.class, op -> switch (op) {
    case Prices.Price p -> answer(p, table.get(p.sku()));
    case Prices.Total t -> answer(t, t.skus().stream().mapToLong(table::get).sum());     // one query for all
  });
}
```

- The `switch` is over a sealed interface and must be whole. Add an operation to `Prices`, and every handler of
  `Prices` that has no clause for it stops compiling.
- `answer(r, value)` takes a value of `r`'s own answer type. `answer(r, "yes")` does not compile.
- The code before `answer` runs when the operation is asked. A handler is where a database, a client, a clock
  are reached; a program never holds one.

The type `Handler<Stock, B, B, R>` is read in section 5. For a handler that only answers it is always this:
`<B, R> Handler<Effect, B, B, R>`.

## 4. Putting the handlers, and running

The handlers are put around a program once, at the entry point, and `run` takes the result.

```java
Order order = run(Fx.<Order>top()
    .handler(clockAt(1700)).handler(ids(100)).handler(refusing()).handler(prices(table)).handler(stock(left))
    .handle(place(good)));
// Order[id=101, email=ann@mail, total=9, at=1700]
```

- `Fx.<Order>top()` starts at the top of an application: nothing is to be left unanswered. `Order` is what the
  whole gives — here what the program gives, since these handlers only answer; javac cannot infer it here, so
  it is said.
- `.handler(h)` puts a handler; the outermost first, each inside those before it, so the last is the nearest to
  the program.
- `.handle(program)` gives a program again, with nothing left to ask; `run` accepts only such a one.

Leave out `.handler(clockAt(1700))` and it does not compile: `place` asks `Clock`, and no handler of it is put.
That is the check that replaces a container failing at start-up.

## 5. A handler in full: a fold

`handling` and `answer` are short forms. In full, a handler is a **fold of the part of the program it is
around**, and has three things:

```java
folding(Effect.class, state, (state, a) -> b, op -> switch (op) { case X x -> on(x, (state, k) -> ..); })
```

| | |
|---|---|
| a state | a value the handler carries from one operation to the next |
| `ret` | what the handler gives where the part ends with a value: of the last state and that value |
| a clause for each operation | given the state and `k`, the rest of the part |

`k` is a function. `k.apply(answer, nextState)` goes on with the rest of the part: the operation is answered,
and the next operation of this effect will be given `nextState`.

```java
static <B, R> Handler<Ids, B, B, R> ids(int from) {
  return folding(Ids.class, from, (last, b) -> b, op -> switch (op) {
    case Ids.Next next -> on(next, (last, k) -> k.apply(last + 1, last + 1));
  });
}
```

The state is a value, not a field. Nothing in `ids` changes; each operation goes on with a new number.

Now the type. `Handler<F, A, B, R>`:

| | |
|---|---|
| `F` | the effect it answers |
| `A` | what the part it is around gives |
| `B` | what the handler gives of it |
| `R` | what the handler itself asks, of the effects that stay outside its part |

`ids` gives what its part gives, so it is `Handler<Ids, B, B, R>`. The next section has handlers where `A` and
`B` differ.

`handling(Effect.class, cases)` is `folding(Effect.class, null, (s, b) -> b, cases)`, and `answer(x, value)` is
`on(x, (s, k) -> k.apply(value, s))` — written that way because most clauses are that.

## 6. One effect, three meanings

`place` refuses by asking `Refusal.Invalid`. What a refusal *does* is not in `place`. Three handlers:

```java
// stop at the first: an exception
static <B, R> Handler<Refusal, B, B, R> refusing() {
  return handling(Refusal.class, op -> { switch (op) {
    case Refusal.Invalid i -> throw new IllegalArgumentException(i.field() + ": " + i.why()); }
  });
}

// no result: the part is left, and gives an empty Optional
static <A, R> Handler<Refusal, A, Optional<A>, R> trying() {
  return folding(Refusal.class, null, (s, a) -> Optional.of(a), op -> leave(Optional.empty()));
}

// all of them: the refusals are the state, and the program goes on
record Checked<A>(A value, List<String> refused) {}

static <A, R> Handler<Refusal, A, Checked<A>, R> collecting() {
  return folding(Refusal.class, List.<String>of(), (refused, a) -> new Checked<>(a, refused), op -> switch (op) {
    case Refusal.Invalid i -> on(i, (refused, k) -> k.apply(null, plus(refused, i.field() + ": " + i.why())));
  });
}
```

The same program, and a cart with a bad address and two lines out of stock:

```java
// stop at the first: throws "email: not an address"
run(Fx.<Order>top().handler(refusing())
    .handler(clockAt(1700)).handler(ids(100)).handler(prices(table)).handler(stock(left)).handle(place(bad)));

// no result: Optional.empty
run(Fx.<Optional<Order>>top().handler(trying())
    .handler(clockAt(1700)).handler(ids(100)).handler(prices(table)).handler(stock(left)).handle(place(bad)));

// all of them, and the program goes on:
// Checked[Order[..], [email: not an address, tea: out of stock, pot: out of stock]]
run(Fx.<Checked<Order>>top().handler(collecting())
    .handler(clockAt(1700)).handler(ids(100)).handler(prices(table)).handler(stock(left)).handle(place(bad)));
```

What to read here:

- `trying` is `Handler<Refusal, A, Optional<A>, R>`: the part gives an `A`, the handler an `Optional<A>`. Its
  `ret` wraps a value; its clause leaves the part. `leave(value)` is a clause for *any* operation of the effect,
  so no `switch` is needed.
- `collecting` goes on after a refusal — `k.apply(null, ..)` — which an exception cannot do. Its `ret` gives
  the last state together with the value.
- A handler's result type is its own, and what the whole gives is said first: `Fx.<Optional<Order>>top()` with
  `trying`, `Fx.<Checked<Order>>top()` with `collecting`. Nothing in `place` changed.

With an interface and three implementations this would be three shapes of `place`: a `throw` through it, a list
of errors carried through it, an `Optional` at every step.

## 7. What a clause can do with the rest

A clause is `(state, k) -> a program that gives what the handler gives`. With `k` in hand it can:

**Go on.** `k.apply(answer, nextState)`. The usual case.

**Not go on.** Give a value of its own: the rest of the part is left, and `ret` is not called.

```java
case Refusal.Invalid i -> on(i, (s, k) -> pure(Optional.empty()))
```

**Do something with what the rest gives.** `k.apply(..)` is a program; `map` and `flatMap` go on after it.

```java
static <B, R> Handler<Clock, B, B, R> timed(List<String> log) {
  return handling(Clock.class, op -> switch (op) {
    case Clock.Now now -> on(now, (s, k) ->
        k.apply(1700L, s).map(result -> { log.add("the rest gave " + result); return result; }));
  });
}
```

That is the shape of a transaction (commit after the rest), a timer, a retry.

**Go on more than once.** `k` is a value and is never replayed: each `k.apply` goes on from the same place.
`Std.all()` is that handler — the rest is run for every option, and the handler gives the list of what each
gave:

```java
Comp<String, Std.Choice> outfit = Std.<String, Std.Choice>choose(List.of("tea", "cup")).flatMap(sku ->
    Std.<Integer, Std.Choice>choose(List.of(1, 2)).map(qty -> qty + " " + sku));

run(Fx.<List<String>>top().handler(all()).handle(outfit)); // [1 tea, 2 tea, 1 cup, 2 cup]
```

A state is a value on each of those ways: a rest gone on with twice goes on with two states.

## 8. Handlers that ask, pass on, and stand inside a program

**A clause is itself a program**, of the row that stays outside the handler's part. So a handler may ask. The
row is the last parameter of its type, and javac checks it like any other.

```java
static <B, R extends Clock> Handler<Mail, B, B, R> stamped(List<String> outbox) {
  return handling(Mail.class, op -> switch (op) {
    case Mail.Send letter -> on(letter, (s, k) -> perform(new Clock.Now()).flatMap(at -> {
      outbox.add(at + " " + letter.to() + ": " + letter.text());
      return k.apply(null, s);
    }));
  });
}
```

`<R extends Clock>`: this handler of `Mail` needs a handler of `Clock` around it.

**A handler that passes on** asks the operation again, of the handler outside, and goes on with what that
answers. It is what a decorator or an aspect is elsewhere — and it is ordinary code:

```java
static <B, R extends Stock> Handler<Stock, B, B, R> audited(List<String> audit) {
  return handling(Stock.class, op -> switch (op) {
    case Stock.Reserve r -> on(r, (s, k) -> perform(r).flatMap(ok -> {       // asked again, of the handler outside
      audit.add(r.sku() + " x" + r.qty() + " -> " + ok);
      return k.apply(ok, s);
    }));
  });
}
```

`<R extends Stock>` says it needs another handler of `Stock` outside. Inside its own clause a handler is not in
force, so `perform(r)` reaches the next one.

**A handler inside a program.** `Fx.<R, B>row()` starts where `Fx.<B>top()` does, but leaves the row `R`
unanswered: the result is a program again, of that row.

```java
static <R extends Stock & Refusal & Clock> Comp<String, R> confirmed(Cart cart, List<String> outbox,
                                                                    List<String> audit) {
  return Fx.<R, String>row().handler(audited(audit)).handler(stamped(outbox)).handle(confirm(cart));
}
```

`confirm` asks `Mail`, `Stock` and `Refusal`. `confirmed` answers `Mail` here and audits `Stock` on its way
out; `Stock`, `Refusal` and `Clock` are still in its type, for whoever is outside.

```java
run(Fx.<String>top().handler(clockAt(1700)).handler(refusing()).handler(stock(left))
    .handle(confirmed(good, outbox, audit)));
// outbox: [1700 ann@mail: reserved]      audit: [tea x2 -> true, cup x1 -> true]
```

## 9. An observer

`run` takes a second argument: something that is given every operation asked, of every effect, as data, before
it is answered.

```java
var shop = Fx.<Order>top().handler(clockAt(1700)).handler(ids(0)).handler(prices(table)).handler(stock(left));

var seen = new ArrayList<String>();
run(shop.handler(refusing()).handle(place(good)), op -> seen.add(op.getClass().getSimpleName()));
// [Reserve, Reserve, Total, Next, Now]
```

A log, a metric, an audit trail, a recording for a test. If it throws, the program stops there: the operation
is not answered and nothing after it is asked — so an observer can refuse as well as see.

For one effect, a handler that passes on (section 8) sees the answers too. The observer is for all of them at
once.

## 10. Closing

```java
ensuring(part, () -> connection.close())
```

`after` is run when the part ends, however it ends: with a value, left by a clause that does not go on with
its rest, or by an exception.

```java
var log = new ArrayList<String>();
assertThrows(IllegalArgumentException.class,
    () -> run(shop.handler(refusing()).handle(ensuring(place(bad), () -> log.add("closed")))));
// log: [closed]
```

`after` is a `Runnable`: it closes, and asks no operation. A rest gone on with twice ends twice and is closed
twice.

## 11. The usual effects

`okay.tiny.Std` has what every application meets, written with nothing but what this guide has shown.

Four of them have a type argument — a state of `S`, a failure of `E`, a stream of `T`, something given of
`E` — and the machine finds a handler by the class of its effect, which has none: a `State<Integer>` and a
`State<String>` would be one effect to it. So these four are **prototypes** of effects: generic operations, and
what handles them. An application makes effects of its own of them, each a type:

```java
sealed interface Count {
  record Ask(State<Integer> op) implements Count, State.Of<Integer, Count> {}
  State.In<Integer, Count> state = State.in(Ask::new);
}
```

Three lines: the type, for the row; its one record, which holds an operation of the prototype; and the
prototype as that effect. It is asked and handled through the last:

```java
static <R extends Count> Comp<String, R> counted() {
  return Count.state.update(n -> n + 1).flatMap(x -> Count.state.update(n -> n * 10))
      .flatMap(x -> Count.state.get()).map(n -> "at " + n);
}

run(Fx.<Final<Integer, String>>top().handler(Count.state.from(0)).handle(counted()));
// Final[state=10, value=at 10]
```

| Prototype | An effect made of it asks | Its handlers | The part gives |
|---|---|---|---|
| `State<S>` | `.get()`, `.put(s)`, `.update(f)` | `.from(initial)` | `Final<S, A>` |
| `Fail<E>` | `.with(error)` | `.option()`, `.result()` | `Optional<A>`, `Result<E, A>` |
| `Emit<T>` | `.tell(item)` | `.told()` | `Told<T, A>` |
| `Env<E>` | `.ask()` | `.given(value)` | `A` |

`Count` is an effect like one written by hand. javac wants its handler; two states of two types are two types
in one row, `<R extends Count & Note>` (`StdTest.twoStatesOfOneType`); nothing is said at the start of a chain,
since the types are the effect's; and a handler of one never sees an operation of another — a state put by a
method around a part it knows nothing of does not answer that part's own
(`StdTest.aStatePutAroundAPartDoesNotAnswerThePartsOwn`). An observer sees the record; what it holds is
`op()`, of `Std.Of`.

A prototype of your own is written the same way: its operations, generic; `Of`, what an effect's record
implements; and `In`, which makes those operations into the effect's and has its handlers. The record's answer
is an `Object`: which operation it holds, and so what it is answered with, is known where it is asked and where
it is handled — both in `In` — and said there by a cast. Outside `In` everything has its type.

Two effects have no type argument and are used as they are:

| Effect | Asked with | Handler | The part gives |
|---|---|---|---|
| `Choice` | `choose(options)` | `all()` | `List<A>` |
| `Async` | `await(start)`, `awaitAll(starts)` | `blocking()`; or `start(program)` | `A`; a `CompletableFuture<A>` |

The order of two handlers is meaning. A failure inside a state keeps the state; a state inside a failure is
lost with the part (`StdTest.theOrderOfTwoHandlersIsTheOrderOfTwoMonads`):

```java
run(Fx.<Final<Integer, Result<String, String>>>top().handler(Count.state.from(0)).handler(Odd.fail.result())
    .handle(countThenFail()));
// Final[state=1, value=Failed[error=no]]

run(Fx.<Result<String, Final<Integer, String>>>top().handler(Odd.fail.result()).handler(Count.state.from(0))
    .handle(countThenFail()));
// Failed[error=no]
```

`Async` has a page of its own: [Asynchrony as an effect](async.md).

`Std` also has the usual shapes of a program — what a loop and an `if` are in a chain — so that they are not
written as a recursion each time:

| | |
|---|---|
| `forEach(items, item -> program)` | the body for each item, in their order: `Comp<Void, R>` |
| `collect(items, item -> program)` | what the body gave for each: `Comp<List<Y>, R>` |
| `fold(items, initial, (state, item) -> program)` | a state carried through the items: `Comp<S, R>` |
| `iterate(initial, state -> goesOn, state -> program)` | a loop with no list: the step again while the state says so |
| `when(condition, program)` | the program where the condition holds: `Comp<Void, R>` |
| `zip(one, other, (a, b) -> c)` | one, then the other, and a value of both |

```java
static <R extends Count & Log> Comp<Integer, R> basket(List<String> items) {
  return Std.<String, R>forEach(items, StdTest::<R>take)
      .flatMap(x -> Count.state.get())
      .flatMap(sum -> Std.<R>when(sum > 5, Log.emit.tell("heavy")).map(x -> sum));
}

static <R extends Count & Log> Comp<Void, R> take(String item) {
  return zip(Log.emit.tell("took " + item), Count.state.update(n -> n + item.length()), (told, counted) -> null);
}
```

Each is a program: nothing of a body is run when it is made, and a long loop takes no stack. A body may ask
less than the whole does (`length` only tells; `lengths` also has a state). Under a rest gone on with twice
each way goes through the items from where it was, with what it had collected (`StdTest.aLoopInEveryWay`).
Where one of them is what a method returns, javac infers its types; where a chain begins with it, they are
said: `Std.<String, R>forEach(..).flatMap(..)`.

## 12. An entry point of an application

In a service, the place where handlers are put is an ordinary object whose methods are the entry points. It
holds what the handlers reach — here two maps and a counter; in a real one, repositories and clients.

```java
static final class Desk {
  private final Map<String, Integer> left;
  private final Map<String, Long> table;
  private int lastId;

  Optional<Order> place(Cart cart) {
    return run(Fx.<Optional<Order>>top()
        .handler(clockAt(System.currentTimeMillis()))
        .handler(handling(Ids.class, op -> switch (op) { case Ids.Next next -> answer(next, ++lastId); }))
        .handler(trying())
        .handler(prices(table))
        .handler(stock(left))
        .handle(GuideTest.place(cart)));
  }
}
```

`Desk` is what a container would make and inject. `place(cart)`, the business logic, knows nothing of it.
[From ports and adapters](ports-and-adapters.md) takes a service written with interfaces to this, one port at a
time.

## 13. Tests

A test puts other handlers. There is nothing to mock and no container to start:

```java
run(Fx.<Order>top()
    .handler(clockAt(0)).handler(ids(0)).handler(refusing())
    .handler(prices(Map.of("tea", 4L))).handler(stock(Map.of("tea", 5)))
    .handle(place(cart)));
```

Three things a test can check that a mock cannot easily:

- **What was asked, as values.** Records are equal by their fields: `run(program, seen::add)` and
  `assertEquals(List.of(new Stock.Reserve("tea", 2), ..), seen)`.
- **That nothing else was asked.** The row is the list; a program that reads a clock has `Clock` in its type.
- **Every meaning of an effect.** The three handlers of section 6 are three tests of one program.

## 14. When something is wrong

**javac refuses** (`src/test/negative` has a program for each, and `NegativeTest` checks the reason):

| javac says | It is |
|---|---|
| `incompatible types: inference variable R has incompatible bounds` | an operation of an effect the row does not have; or an effect left without a handler |
| `the switch expression does not cover all possible input values` | an operation of the effect without a clause |
| `incompatible types: String cannot be converted to Long` | an answer, or a next state, of another type |
| `no suitable method found for run` | an effect still in the row of what is run |
| `inference variable F has incompatible equality constraints Clock,Stock` | a record of `Clock` that says `Op<.., Stock>`: its handler's clause is of another effect |
| `method handler in class Handlers cannot be applied` | a handler that asks an effect that does not stay around it; or one put around a part of another type |

**When it runs:**

- An exception thrown in a program, a clause or an observer is thrown on as it is. It carries one suppressed
  `Fx.Where`: the operation asked last, and the handlers in force, the nearest first.
- `a clause made for X, asked was Y`: `on` or `answer` was given another operation than the one matched.

**Worth knowing:**

- The type said at `top()` and `row()` is what the whole gives, not what the part does: with `trying` around a
  part that gives an `Order` it is `Optional<Order>`. A handler whose result does not say what its part gives —
  `Handler<Mail, A, String, R>` — has to be told: `Shop.<List<String>, Object>logging()`.
- A handler's row is exactly what is around it. One that asks an effect is written generic in its row,
  `<R extends Clock>`; one declared with a row of its own — `Handler<Mail, B, B, Object>` — can be put only where
  that is the row around it. That is what makes the rest a clause is given say all it still asks.
- Handlers put once and used for several programs are a `var`: the type of the chain names a row javac
  inferred, which cannot be written.
- A row of several effects cannot be written as a type argument — `Fx.<Stock & Clock, A>row()` is not Java.
  Put the handlers in a method whose type variable is that row: `<R extends Stock & Clock>`.
- A handler is found by its effect's class, and a class has no type argument: an effect with one is written as
  a prototype, and each use of it is an effect of its own, as `Std`'s are (section 11).
- Mutable state captured by a handler is shared by every way of a rest gone on with twice. The handler's own
  state — the second argument of `folding` — is not.
- Do not keep a `k` past its clause — unless the clause is made with `keep`, which is for that
  ([Asynchrony as an effect](async.md)).
