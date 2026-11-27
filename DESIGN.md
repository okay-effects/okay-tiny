# The design, and why

The kernel is `src/main/java/okay/tiny/Fx.java`, 250 lines of code. This page records what was decided, what in
Java forced the shape of it, what was tried and left, and what an operation costs — so that none of it has to be
found again.

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
  have one type. `Case` is sealed, over one private record that has the answer's type: a clause of another answer type cannot be put there by a cast.
- **That is as short as it gets.** The effect is named twice on a record — `implements Stock, Op<Boolean, Stock>` —
  and every way to say it once was tried and is refused for the same reason, a generic supertype that two effects
  of one row give different arguments: `Stock extends Op<A, Stock>`; an answer-indexed family,
  `Eff<T> extends Op<T, Eff<?>>`; `Stock extends Effect<Stock>` with `Op<Boolean>` on the record. A family with
  only the answer, `Eff<T> extends Op<T>`, is accepted, but then `perform` needs the family's class, rows are
  `Eff<?> & ..`, a `case` still does not refine `T`, and `Get<S> implements State<S, S>` cannot be matched. A
  record that names another effect than its own is caught where its effect's handler is written: the clause is
  of the other effect (`OperationOfAnotherFamily`).
- **One effect of a class in a row.** `<R extends State<Integer> & State<String>>` is refused by javac, which is
  as well: a handler is found by the effect's class.
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
- `run(program, see)` gives an observer every operation asked, before it is answered; what it throws stops the
  program there. It is the one thing a handler cannot do alone — see the operations of every effect — and is the
  second argument of `run`, not a part of a program: an application has one.
- A rest gone on with twice ends twice, and is closed twice. A rest kept and gone on with after its clause has
  ended is closed when that clause ends, and again when it ends itself — unless the clause is made with `keep`.
- `keep(op, clause)` is `on` without the mark under the clause: the rest is not taken for left when the clause
  ends. Its keeper goes on with it later, in a run of its own — `run(k.apply(x, s))`: `k` is a value, and the
  parts taken with it go back on top of an empty stack as on any other. It is all the kernel has for
  asynchrony: `Std.Async` (an operation that holds the work to start; `blocking()`, where the thread waits, and
  `start`, where the rest goes on from the callback of a `CompletableFuture`) is written with it and nothing
  else. That a kept rest asks nothing outside is not in the types for a handler put by hand; `start` takes a
  `Comp<A, Async>` and puts the handler itself, so there it is.

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
| in a rest put back, always a copy of the states with those inside copied in — without the case where nothing around has changed, which is one `clone` | a state outermost of three cost 36 ns an operation, not 30 |

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

**Tail calls.** A call in the tail of a function given to `flatMap` leaves nothing in the stack of steps: two
functions that call each other 10^8 times run in a stack of 256k and a heap of 64m, at 43 ns a turn with a
state read and written (two operations), 2.2 ns with nothing asked (`defer`). Not tail calls, measured too: a
call with a `map` after it, or inside `ensuring` — the heap grows by a step a level; a call inside a handler put
anew — a part a level, at the same cost each (3·10^6 levels: 0.4 s).

`defer` is a constructor of the program, and one line of the loop. As `pure(null).flatMap(..)` it was two turns
and four objects, 5.9 ns; it is the one word a program without it cannot say cheaply — a call put off — and the
kernel's own `Part` was already such a thing for handlers. It is the last case of the loop's `switch`: put
before `Part` and `Perform` it cost a counter 3 ns an operation.

The array of states is written in place while no rest taken off has it (section 3). Before that every new part
copied it, and parts one in another cost the square of their depth: 10^5 levels, 0.6 s; now 0.05 s.

**How far these numbers hold.** They are of a machine with nothing else running. With another process busy, a
state outermost of seven gave 33 to 41 ns from one run to the next, for this kernel and for the one before it
alike: two kernels are compared by runs taken in turn, several of each, and by their least.

## 6. What is not here

- **Direct style, in the kernel.** Without threads or a transformation there is none in Java. It is beside the
  kernel, as a plugin of javac that rewrites a method into the chain it stands for before its types are checked
  (`direct/`, `docs/direct.md`): the kernel does not know of it, and what runs is `flatMap`.
- **Cancelling, timeouts, and programs run side by side.** `Async` waits for work; it does not stop it, and two
  programs at once are two `start`.
- **Finalizers that ask.** `ensuring(part, after)` runs a `Runnable`: it closes, and asks no operation.
- **The laws.** That `ret` and the clauses of a handler make a lawful monad is their author's: a clause that
  applies `k` twice and keeps one result passes the types.
- **A handler generic in its effect that still knows its class** — it is given the class by its caller.
- **Stack traces of the program.** A failure shows the machine's loop and the function that threw. What is added
  to the exception (`Fx.Where`, suppressed; the exception itself is thrown on as it is) is the operation asked
  last and the handlers in force — not the path through the program.

## 7. Open

The family's TCK passes (`TckTest`): a thousand random programs of logs, states, failures and choices one in
another, and the digest of their outcomes is the reference's, `-8623788747993822161` — the same number as the
Scala, Java and Rust kernels of okay-effects. A handler there answers only its own identity and asks the others
again outside, which here is an ordinary clause.


It has been tried on one real flow, a booking of seven effects that was already written with effects
(`Booking`), and in a small Spring Boot service with a database, Spring's transactions and a controller
(`spring-sample`, `docs/spring.md`). That one found nothing to change in the kernel: a program runs on the
thread that called `run`, so what Spring binds to a thread is where the handlers expect it, and `ensuring` was
enough for a transaction around a part. It has not been tried on a large code base, with JPA, or by a team.
Those are the next things to learn from.
