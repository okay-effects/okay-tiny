# Reference

Everything `okay.tiny.Fx` and `okay.tiny.Std` have. The [guide](guide.md) explains; this page lists.

## Types

| Type | What it is |
|---|---|
| `Op<A, F>` | an operation: `A` is the type of its answer, `F` its effect. A record implements it. `of()`: what a generic record of a template is of — the template as it was said for one effect, an object; none where the record is the effect's own |
| `Comp<A, R>` | a program: `A` is what it gives, `R` the effects it may ask |
| `Handler<F, A, B, R>` | a handler of the effect `F`: the part it is around gives `A`, it gives `B`, its clauses ask `R` |
| `Clause<X, S, B, R>` | what a handler does for one operation: `(state, k) -> Comp<B, R>`; `X` is the operation's answer type |
| `Case<F, S, B, R>` | a clause together with the operation it was made for; made only by `on`, `answer`, `leave` |
| `Handlers<R, W, A, B>` | handlers being put, the outermost first: `R` stays, they give `B`; `W` is the row inside them, and `A` what is to be given there |
| `Fx.Where` | added (suppressed) to an exception thrown out of `run`: the operation asked last, the handlers in force |

`k`, the rest of a part, is a `BiFunction<X, S, Comp<B, R>>`: the answer and the next state.

## Making a program

| | | |
|---|---|---|
| `pure(a)` | `Comp<A, R>` | asks nothing, gives `a` |
| `perform(op)` | `Comp<A, R>`, `R extends F` | asks `op`, gives its answer |
| `p.flatMap(a -> q)` | `Comp<B, Q>`, `Q extends R` | `p`, then `q` made of what it gave |
| `p.map(a -> b)` | `Comp<B, Q>`, `Q extends R` | `p`, then `b` made of what it gave |
| `ensuring(part, after)` | `Comp<A, R>` | `part`; `after` is run when it ends, however it ends |
| `defer(() -> program)` | `Comp<A, R>` | the program, made when it is reached: a call with nothing asked before it, as a step of the machine |

`Q extends R` is how a program of fewer effects is one of more: the row of a chain is the row of its parts.

## Making a handler

| | |
|---|---|
| `folding(E.class, state, ret, op -> switch (op) { .. })` | a handler: the initial state, `ret: (lastState, a) -> b`, a `Case` for each operation |
| `handling(E.class, op -> switch (op) { .. })` | one with no state that gives what its part gives: `Handler<E, B, B, R>` |
| `on(op, (state, k) -> ..)` | the clause for the operation matched; `k.apply(answer, nextState)` goes on with the rest |
| `answer(op, value)` | `on(op, (s, k) -> k.apply(value, s))`, answered where it is asked |
| `leave(value)` | a clause for any operation of the effect: the part is left, the handler gives `value` |
| `by(of, state, ret, cases)` | a handler found by the object `of` that its operations say (`Op.of()`), not by a class: for a template. Its clauses are given the operations as what the template made them |
| `keep(op, (state, k) -> ..)` | `on` for a clause that keeps `k` to go on with it after the clause has ended, in a run of its own: the rest is not closed when the clause ends |

A clause may: apply `k` once (answer); not apply it (the part is left with the clause's own value, and `ret` is
not called); apply it and go on after it with `map` or `flatMap`; apply it more than once. It is a program of
the row `R`, so it may `perform` what stays outside its part.

## Putting handlers

| | |
|---|---|
| `Fx.<R, B>row()` | handlers, the outermost first, around a part of a program: the whole gives `B`; the row `R` is left unanswered |
| `Fx.<B>top()` | the same with nothing left: `Fx.<Object, B>row()` |
| `.handler(h)` | `h` inside those put before it; its row is exactly what is around it — `R` and the effects of those before it |
| `.handle(part)` | the part with the handlers around it: `Comp<B, R>` |

## Running

| | |
|---|---|
| `run(program)` | runs a program with nothing left to ask, and gives its value |
| `run(program, see)` | the same; `see` is given every operation asked before it is answered, and stops the program by throwing |

`run` is called at an entry point. A program does not call it: to answer an effect inside a program, put a
handler there with `row()`.

## Std

| Effect | Operations | Functions | Handlers |
|---|---|---|---|
| `State<S, F>` | `Get`, `Put` | `.get()`, `.put(s)`, `.update(f)` | `.from(initial)`: `Handler<F, A, Final<S, A>, R>` |
| `Fail<E, F>` | `Raise` | `.with(error)`, of any type | `.option()`: `Optional<A>`; `.result()`: `Result<E, A>` |
| `Emit<T, F>` | `Tell` | `.tell(item)` | `.told()`: `Told<T, A>` |
| `Env<E, F>` | `Ask` | `.ask()` | `.given(value)`: `A` |
| `Choice` | `Of<X>` | `choose(options)` | `all()`: `List<A>` |
| `Async` | `Await<X>` | `await(start)`, `awaitAll(starts)` | `blocking()`: `A`; `start(program)`, `start(program, see)`: runs a `Comp<A, Async>`, gives a `CompletableFuture<A>` |

| The shape | |
|---|---|
| `forEach(items, body)` | `Comp<Void, R>`: the body for each item, in their order |
| `collect(items, body)` | `Comp<List<Y>, R>`: what the body gave for each |
| `fold(items, initial, step)` | `Comp<S, R>`: `step: (state, item) -> Comp<S, R>` |
| `iterate(initial, goesOn, step)` | `Comp<S, R>`: `step` again while `goesOn(state)` |
| `when(condition, program)` | `Comp<Void, R>` |
| `zip(one, other, both)` | `Comp<C, R>`: one, then the other, `both(a, b)` |

Items are any `Iterable`, read once into a list when the program is made. A body's row may be less than `R`.

`Final<S, A>(state, value)`, `Told<X, A>(items, value)`, `Result<E, A>` = `Ok(value)` or `Failed(error)`.
The first four are templates: `F` is the effect said of one, a type of the application's —

```java
interface Count { State<Integer, Count> state = State.of(Count.class); }
```

— and the functions and handlers in their rows are reached through what was made: `Count.state.get()`,
`Count.state.from(0)`. The object made is what tells the effect from another: say it once, as a constant.

## What javac refuses

| | |
|---|---|
| an operation of an effect the row is not bounded by | `inference variable R has incompatible bounds` |
| an effect left without a handler | the same, at `handle` |
| a `switch` that leaves out an operation of the effect | `the switch expression does not cover all possible input values` |
| an answer or a next state of another type | `X cannot be converted to Y` |
| `ret` or a clause that gives another type than the handler's | `inference variable .. has incompatible bounds` |
| a clause for an operation of another effect | `incompatible types` |
| a handler that asks an effect that does not stay around it | `method handler in class Handlers cannot be applied` |
| a handler around a part of another type | the same |
| `run` of a program with an effect still in its row | `no suitable method found for run` |

## What is refused when it runs

| | |
|---|---|
| `a clause made for X, asked was Y` | `on` or `answer` was given another operation than the one matched |
| `NullPointerException` naming it | a null operation, effect class, `ret`, clauses, or handler |
| `no handler for X` | cannot be reached by a program javac accepted without an unchecked cast |

## Costs

JMH, Temurin 21, Apple silicon (`./gradlew jmh`); ns an operation:

| | ns |
|---|---|
| `answer`, the handler the innermost of seven / the outermost | 10 / 15 |
| a clause that goes on, with a state that moves on: alone / the innermost of seven / the outermost of seven | 19 / 21 / 33 |
| each `map` | about 5 |

`DESIGN.md` has the table in full and what was given up for it.
