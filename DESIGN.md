# The design, and why

The kernel is `src/main/java/okay/tiny/Fx.java`. This page records what was decided, what in Java forced the
shape of it, what was tried and left, and what an operation costs — so that none of it has to be found again.

## 1. The decisions

1. **A program is a free monad.** `Comp<A, R>` is data: `Pure`, `Perform(op)`, `FlatMap`, `Mapped`, `Part`
   (a handler around a part), `Resume` (a rest gone on with). No threads, no bytecode transformation: direct style
   is given up for that, programs are written with `flatMap` and `map`.
2. **A handler is a fold of the part it is around** — a combination of fold and `flatMap`. It always has a state,
   always has `ret` (what it gives where the part ends with a value: of the last state and that value), and a
   clause for every operation of its effect. `Handler<F, A, B, R>`: the part gives `A`, the handler `B`, and its
   clauses ask the row `R` that stays outside the part.
3. **An effect is a signature; the handler is the monad that implements it.** `ret` is the unit, a clause with
   its `k` is the bind of that operation, the state and the result type are the carrier. One effect has many
   handlers: a refusal folded to an `Optional`, to a list of refusals with the value, to an exception.
4. **The rest of the part, `k`, is a plain function**: `BiFunction<X, S, Comp<B, R>>` — of the operation's own
   answer type and the next state. No witness is passed with the answer.
5. **`k` is a value.** It may be applied once, more than once, or never, and is never replayed. So is the state:
   a rest gone on with twice goes on with two states.
6. **Sets of operations are not in the kernel.** Many at once is an ordinary operation that holds a list; its
   handler sees them all before it answers (one query, a sum, a way for each).
7. **The names**: `row`, `top`, `handler`, `handle` for where handlers are put; `folding`, `handling`, `on`,
   `answer` for making them.

## 2. What Java forced

- **The answer's type is on the operation's record, not on the effect.**
  `sealed interface Stock extends Op<Boolean, Stock>` would be the natural way to say it, and a row of two such
  effects, `<R extends Stock & Refusal>`, is then refused: *Op cannot be inherited with different arguments*.
  So an effect interface has no generic supertype, and each record says `Op<Answer, Effect>`.
- **A clause for each operation, made by `on(op, ..)`.** With one clause for the whole effect, `k` would have to
  take answers of different types, and Java does not refine a type in a `case`. `on(p, clause)` reads the answer
  type off `p` once, and gives a `Case<F, S, B, R>` in which that type is hidden — so the arms of the `switch`
  have one type. `Case` has no public constructor: a clause of another answer type cannot be put there by a cast.
- **Completeness is javac's.** A handler is `op -> switch (op) { .. }` over a sealed interface, and a `switch`
  expression that leaves an operation out does not compile.
- **The row is a set, and is inferred in an earlier call.** `Fx.<R, A>row().handler(h).handle(part)` is in this
  order because javac must fix the set of effects — `R` and each handler's — before it sees the part. With the
  handlers and the part in one call it infers the set from what the part asks, and accepts a part that asks more.
- **`R` and `A` are written at `row()`.** Handlers that only answer are generic in the part's type, so javac has
  nothing to infer it from.
- **The effect's class is passed** (`folding(Stock.class, ..)`). The machine needs it to route an operation.
  Reading it from an empty varargs array (`F... effect`) works and was tried; it was taken back: a hidden
  parameter, a class read at run time, and a pitfall where a handler is made generic in its effect.
- **A row of several effects cannot be written as a type argument.** `Fx.<Stock & Clock, A>row()` is not Java:
  such a row is a type variable of the method that puts the handlers, `<R extends Stock & Clock>`.

## 3. The machine

One loop. The rest of the program is a stack of parts — one for each handler in force — each a stack of steps.

- `perform(op)`: the nearest part whose handler's effect the operation is of is found. A clause made by `answer`
  is answered in place, nothing taken off. Otherwise the rest, to the end of that part, is taken off as `k`, and
  the clause runs as a program of what is around that part.
- `k.apply(answer, next)`: the parts taken off go back on top of what is run now. The states of the parts inside
  the handler's own are as they were when the operation was asked; those around it are as they are now; its own
  is `next`. This is the one place of the kernel that needs its picture (in the comment there).
- The end of a part: `ret` of the handler's state and the part's value.
- The state of the innermost part is in a variable of the loop, those of the others in an array by depth, never
  changed in place once shared. So a state that moves on costs an assignment for the innermost handler and one
  copy of the array for another — not a copy of every part inside it.

## 3a. Leaving, closing, failing

- `leave(value)` is a clause for any operation of its effect. It has no operation to read the answer's type
  from, so it cannot go on with the rest: it only leaves the part.
- `ensuring(part, after)` puts a mark among the steps. The value passes it and `after` is run: the part has ended.
  When a clause is given the rest, a mark with that rest is put under the clause; when the clause ends and the
  rest was never gone on with, it was left, and the marks in it are run, the innermost first. A clause that only
  goes on takes its mark off with it. Under an exception every mark still in the stack is run, and what a closing
  throws is added to the exception.
- A rest gone on with twice ends twice, and is closed twice. A rest kept and gone on with after its clause has
  ended is closed when that clause ends, and again when it ends itself: do not keep one.

## 4. Tried, and left

| Tried | Why it was left |
|---|---|
| a handler that only answers as a kind of its own, answered in place | one kind of handler; `answer` keeps the speed without a second kind |
| `leaving(..)` and an opaque `End` for the part's value, so that the part's type is inferred | a clause that does not apply `k` says the same; the price is `A` written at `row()` |
| sets of operations in the kernel (`perform(a, b)`, a function of answers, an `Iterable` of answers) | an operation that holds a list does it with no support |
| one clause for the whole effect with `k.resume(op, answer)` | `k` stops being a function; what it gave — any operation leaves the part — is `leave(value)` |
| a block of named answers asked in rounds (`plan`) | it lived on sets of operations |
| the state in the stack's records | a handler with a state outside six others cost 57 ns an operation, not 30 |
| the effect's class read from an empty varargs array | see above |

## 5. What it costs

JMH 1.37, Temurin 21, Apple silicon; ns an operation, each ± under 0.5 (`./gradlew jmh`):

| | ns |
|---|---|
| `answer`, the handler the innermost of seven | 10.1 |
| `answer`, the handler the outermost of seven | 15.1 |
| an answer and two `map` | 20.5 |
| a counter (a state that moves on at every operation), alone | 18.9 |
| the counter the innermost of seven | 20.8 |
| the counter the outermost of three | 29.5 |
| the counter the outermost of seven | 32.9 |

A free monad makes five or six small objects for an operation; that is the floor. Next to a query to a database
it is nothing. `ensuring` and the note of where a program failed cost one to two of these nanoseconds: a mark
under every clause that is given the rest, and a test for marks at every step.

## 6. What is not here

- **Direct style.** Without threads or a bytecode transformation there is none in Java; okay-java has it.
- **Finalizers that ask.** `ensuring(part, after)` runs a `Runnable`: it closes, and asks no operation.
- **The laws.** That `ret` and the clauses of a handler make a lawful monad is their author's: a clause that
  applies `k` twice and keeps one result passes the types.
- **A handler generic in its effect that still knows its class** — it is given the class by its caller.
- **Stack traces of the program.** A failure shows the machine's loop and the function that threw. What is added
  to the exception (`Fx.Where`, suppressed; the exception itself is thrown on as it is) is the operation asked
  last and the handlers in force — not the path through the program.

## 7. Open

It has been tried on one real flow, a booking of seven effects that was already written with effects
(`Booking`). It has not been tried on a service of beans and transactions. That is the next thing to learn from.
