# The design, and why

The kernel is `src/main/java/okay/tiny/Fx.java`, 398 lines of code. This page records what was decided, what in
Java forced the shape of it, what was tried and left, and what an operation costs — so that none of it has to be
found again.

## 1. The decisions

1. **A program is a free monad.** `Comp<A, R>` is data: `Pure`, `Perform(op)`, `Asking(op, next)`, `FlatMap`, `Mapped`, `Part`
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

## 1a. The rule of the code

Nothing is weakly typed where a type exists. What has several kinds is a sealed type of records, one for each
kind — the nodes of a program, the steps of the machine. What must hide a type from the outside hides it as an
existential: a sealed interface without that type, over private records with it — `Case` over `Answer<X, ..>`,
`On<X, ..>`, `Keep<X, ..>` and `Leave` (the answer's type), `Handler` over `Fold<.., S, ..>` (the state's), and
what the machine is at, `At<Z>`, over `Run<A, Y, Z>`, `Give<A, Y, Z>` and `Done<Z>`. Nothing in the machine is
`Object`: `docs/machine.md` has the types and the few places taken on trust.

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
- **An effect with a type argument is a template, and each use of it an effect of its own.** The class is all
  the machine has of an effect: `State<Integer>` and `State<String>` are one to it. javac refuses the two in one
  row — *State cannot be inherited with different arguments* — but not where a row is hidden: a method generic
  in its part's row put a `State<String>` around the part, the part asked a `State<Integer>` meant for outside,
  and it was answered with a `String`: `ClassCastException`, in a program javac had passed. And no type
  argument — of the state, or a name beside it — can tell two of them apart in a row, for the same refusal
  meets any generic supertype they share: an effect cannot be made of a template by `extends`.
  So an effect is *said of* a template: `interface Count { State<Integer, Count> state = State.of(Count.class); }`.
  The type is the application's own and has no supertype, so any two are in one row; the template's operations
  are generic records that hold what was made — `Count.state`, a `Template<Count>` — and say it (`Op.of()`),
  and the machine finds a
  handler by what an operation says it is of, where it says, and by its class where it does not. The type of
  the state is javac's from the declaration — `State<Integer, Count>` — to every use, and it is the object's:
  an operation and its handler are of one object, so of one type. `by(of, state, ret, cases)` is `folding` for
  such a handler: its clauses are given the operations as what the template made them, and the one cast that
  says so is in the kernel, beside the search that makes it true. A template said twice for one class is two
  effects, and the handler of one does not answer the other: what javac cannot see there is a missing handler,
  not a wrong type. (First the operations held the effect's class, and two sayings of two types were one
  effect to the machine; a table of the classes said, kept to refuse the second, was taken out.)
  Tried and taken back on the way: a `Key<T>` that operations and handler shared, with `pass()`, a clause by
  which a handler let an operation go on outside — a value at every use, checked when the program runs; an
  effect's own record that held a template's operation, with the answer in its type (a constructor passed as
  a method generic in the answer, each operation giving its own clause) or with an `Object` for it and a cast
  — three lines for an effect, and too much to read.
- **One effect of a class in a row.** `<R extends State<Integer> & State<String>>` is refused by javac, which is
  as well: a handler is found by the effect's class.
- **Completeness is javac's.** A handler is `op -> switch (op) { .. }` over a sealed interface, and a `switch`
  expression that leaves an operation out does not compile.
- **The row is a set, and is inferred in an earlier call.** `Fx.<R, B>row().handler(h).handle(part)` is in this
  order because javac must fix the set of effects — `R` and each handler's — before it sees the part. With the
  handlers and the part in one call it infers the set from what the part asks, and accepts a part that asks more.
- **`R` and `B` are written at `row()`**: the row that stays, and what the whole gives. Handlers that only answer
  are generic in the part's type, so javac has nothing to infer it from.
- **Handlers are put outermost first, each with exactly the row around it.** Only so is that row known when a
  handler is put: it is what the handlers before it have made. A handler's clauses ask it, and the rest they
  are given is typed with it — so the rest says all it still asks. Put innermost first, as they were, a
  handler's row was the one outside them all, and its rest, which still asks the effects of the handlers
  between, said less than it asks: a clause that ran its rest by itself, `Fx.run(k.apply(x, s))`, in a handler
  with an empty row inside a handler of the clock, compiled and failed with *no handler for Now*. javac refuses
  that now (`RestRunByItself`). The price: what is said at `row()` is what the whole gives; a handler whose
  result does not say what its part gives has to be told it; and a handler that asks is generic in its row.
- **The effect's class is passed** (`folding(Stock.class, ..)`). The machine needs it to route an operation.
  Reading it from an empty varargs array (`F... effect`) works and was tried; it was taken back: a hidden
  parameter, a class read at run time, and a pitfall where a handler is made generic in its effect.
- **A row of several effects cannot be written as a type argument.** `Fx.<Stock & Clock, A>row()` is not Java:
  such a row is a type variable of the method that puts the handlers, `<R extends Stock & Clock>`.

## 3. The machine

A trampoline of typed moves. The rest of the program is a stack of parts — one for each handler in force — each
a stack of steps: steps are `Rest<A, Y>`, from a value of `A` to the value of their part; parts are
`Around<Y, Z>`, from what the innermost gives to what the program does. What the machine is at is a value,
`At<Z>` — a program of `A` to run, or a value of `A` to give, with steps from `A` to `Y` and parts from `Y` to
`Z` — and a move takes one and gives the next. javac checks that the three fit at every move.

- `perform(op)`: the nearest part whose handler's effect the operation is of is found. A clause made by `answer`
  is answered in place, nothing taken off. Otherwise the rest, to the end of that part, is taken off as `k`, and
  the clause runs as a program of what is around that part.
- `k.apply(answer, next)`: the parts taken off go back on top of what is run now. The states of the parts inside
  the handler's own are as they were when the operation was asked; those around it are as they are now; its own
  is `next`. They go back as parts made anew of those taken off, which the rest goes on holding.
- The end of a part: `ret` of the handler's state and the part's value.
- The state of a handler is in the record of its part, with the handler, and moves on there: a new state is
  one write, whichever part it is. A part that is run is the machine's alone — a rest taken off takes its parts
  with it, and goes on with parts made anew — so nothing is copied to keep a rest's states its own.

## 3a. Leaving, closing, failing

- `leave(value)` is a clause for any operation of its effect. It has no operation to read the answer's type
  from, so it cannot go on with the rest: it only leaves the part. No rest is made for it: what the part was
  to close is closed there and then, and the value goes on below.
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
| the machine as one loop over variables of type `Object` — the program, the value, the steps, the parts — with casts where a hidden type was opened | a trampoline of typed moves over `At<Z>`. A state costs the same or less; an operation answered in place 6.0 ns, not 5.0 (section 5 says of what); 118 lines more |
| the typed moves calling each other, the three put down in fields every 32 calls | a call stack as the mechanism; the trampoline needs no fields and reads nothing on trust |
| one interface for which operations are a handler's own — `Own<O, F>`, a function from any operation to it as the clauses take it, or none — in place of `Op.of()`, `Template` and the two kinds of finding; `Op` a marker with no method, the templates an ordinary use of the kernel | a handler found by an object then asks every operation what it says, at every part it is not of: a state outside four other effects of templates cost 23 ns an operation, not 15.5 (`StateBench.templateOutermostOfFiveTemplates`), and every other lane 0.5 to 3 ns more. `of()` is asked once an operation, and a miss is one comparison |
| `Std`'s state, failure, stream and given as generic effects found by their class — `get()`, `state(0)`, `option(String.class)` | templates, and effects said of them (section 2): found by class alone, a handler of one type answered another's |
| a clause as one record for its four kinds (`answer`, `on`, `keep`, `leave`), with fields left empty and a flag | a sealed type of four records, each with what it needs: `leave` has no operation and no answer's type. An operation answered in place costs the same; one whose clause takes the rest, the same to 2 ns more |
| a step as one record with flags (`mapped`) and a mark of type `Object`; `Case` and `Handler` as classes with `Object` fields | each is a sealed type over records now, the hidden type an existential inside: fewer casts, and the step 2 to 6 ns an operation faster |
| the states of the handlers in an array by the depth of their parts, the innermost in a variable of the loop; copied before a write once a rest had it | each state is in its part's record, with its handler: no array, no depth, no copying. 22 lines less, and a state outermost of seven costs 16.4 ns, not 19.4 |
| in a rest put back, always a copy of the states with those inside copied in — without the case where nothing around has changed, which is one `clone` | a state outermost of three cost 36 ns an operation, not 30 |

## 5. What it costs

JMH 1.37, Temurin 21, Apple silicon; ns an operation, each ± under 0.5 (`./gradlew jmh`):

| | ns |
|---|---|
| `answer`, the handler the innermost of seven | 6.0 |
| `answer`, the handler the outermost of seven | 15.8 |
| an answer and two `map` | 17 to 22 |
| a counter (a state that moves on at every operation), alone | 7.5 |
| the counter the innermost of seven | 7.6 |
| the counter the outermost of three | 11.7 |
| the counter the outermost of seven | 15.6 |
| a state said of `Std`'s template, alone | 13.0 |
| that state the outermost of seven | 13.8 |
| that state outside four other effects said of templates | 15.5 |

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

A part more costs the same however deep it is. When the states were in an array that every new part copied,
parts one in another cost the square of their depth: 10^5 levels, 0.6 s; now 0.05 s.

**How far these numbers hold.** They are of a machine with nothing else running. With another process busy, a
state outermost of seven gave 33 to 41 ns from one run to the next, for this kernel and for the one before it
alike: two kernels are compared by runs taken in turn, several of each, and by their least.

**An operation and what follows it are one node.** `perform(op).flatMap(next)` is `Asking(op, next)` — the node a
free monad is named for — not a `FlatMap` of a `Perform`. Where the operation is answered in place the machine
gives the answer to `next` at once: no step is put on the stack and taken off it, and one object less is made.
An answer cost 10.1 ns and costs 6.9 (the innermost of seven), 15.2 and 12.3 (the outermost).

**A clause that only goes on is answered in place.** `on(op, (s, k) -> k.apply(answer, next))` gives the machine
`k` applied, and nothing else: the machine sees that what the clause gave is its own rest gone on with, and
takes nothing off and puts nothing back — it sets the state and gives the answer. This is the general case of
which `answer(op, value)` is one; no word of its own was needed (one was tried: `answer(op, s -> x, s -> next)`).
A counter cost 18.9 ns and costs 9.0; outermost of seven, 33 and 16.4.

**What a typed move costs, measured.** The loop over `Object` variables answered in place in 5.0 ns; the
trampoline first did in 6.5, with the same 64 bytes made an operation (`-prof gc`), so the difference is code
and not objects. A third of it was the way back to the loop after every answer: the next program written to
the `Run`, a field of an object and so a store with the collector's barrier, and a call through `At`. An
answer in place now goes on within its move, up to `STEPS` times, as a value already did through steps: 6.0.
Of the nanosecond left, 0.3 is the operation kept for `Fx.Where`; the rest is not found — the `of()` asked of
every operation and the `switch` over sealed types are what is new in that path.

**An answer and two `map` gives 17 or 22 ns**, from one fork of the JVM to the next and at times within one,
under G1 and under the parallel collector alike; the kernel before the trampoline gave the same two numbers.
It makes 224 bytes an operation, ten gigabytes a second: it is the speed of making objects that is measured
there, and it is not the kernel's to settle.

**What a handler is found by is a type of two, not two fields of which one is empty.** A handler held a
class and an object, one of them none, a class of no instances for the handler found by an object, and its
clauses under a cast for both. It holds a `Cases`: `OfClass`, which gives its clauses the operation by
`Class.cast`, or `OfObject`, with the one cast a template needs; the type the clauses take is hidden in it,
as the state's is in `Fold`. The search asks each part `has(op, of)`, a call where there were two loads: an
answer from the outermost of seven costs 15.8 ns, not 13.4, and a template's state 13.4 and 15.5, not 15.3
and 17.6. With it went the other places where one type stood for several: a case's answer type is opened
once, for the operation asked, and not in each of three records; the two marks are one kind; `End` and `Top`
are made, not one object of every type; the nodes and the handlers being put say the row they hold. Thirteen
places were marked unchecked, and five are (`docs/machine.md` lists them).

**A clause that only goes on is answered as `answer` answers.** The steps of a rest — the `Then` of what
follows the operation — were made before its clause was called, and the answer was then given through them,
by the loop. No step is made for it now: a rest holds what was asked — the node, which gives the program that
follows from an answer — and the steps after that, so it is whole when its clause is called, and a clause that
only goes on gives its answer straight to what follows, within the move. (For a while the steps were put in
the rest after the clause had returned. A clause made with `keep` may run its rest inside itself — a callback
given to work already done is called there and then — and found it without steps:
`ScenariosTest.aRestKeptMayBeRunWithinItsOwnClause`. Nothing of a rest is written after it is made, but the
flag that it was gone on with.) A counter made 112 bytes an operation and makes 92; it cost
9.0 ns and costs 7.5, a template's state 12.8 and 11.9. It goes on within the move only where the part that
answers is the innermost: for a template's state the outermost of seven, going on within the move cost 19.5
ns where the way back to the loop costs 14.6 (it was 15.5) — a longer move, which the JIT no longer made one
piece of.

**A template costs what its program costs.** A state said of `Std`'s template makes 187 bytes an operation
where the counter makes 92, and that is not the template: the same program over an effect written by hand and
found by its class (`StateBench.byHandAlone`) makes 172 and takes the same time within what a run varies by.
The difference from the counter is the program's — an operation that holds a boxed value, a clause that
reads it, a handler of two operations and not one.

**A rest that is kept is always taken off.** Where a clause made with `keep` went on with its rest at once,
the machine went on in place and gave the rest copies of its parts, lest the ones being run change under it:
a second way to hold parts, with two fields and three methods of its own in `K`. A kept rest now goes the one
way every rest taken off goes, and nothing a rest holds is ever run. It costs that path 12 ns — and `Std`'s
asynchrony, the one place that went that way, does not: with nothing to wait for it answers with `answer`
(`StateBench.awaitDone`: 43 ns before and after).

**Asynchrony: a callback called as it is given.** `Std.start` ran the rest from the callback of the work
waited for. A `CompletableFuture` that has ended calls a callback where it is given: for work that ended
between the test for it and the giving, the rest ran inside the clause, a run in a run, and with work that
ends quickly the stack only grew — measured, 27 frames at a wait became 86 to 888 over twenty thousand
waits, and a `StackOverflowError` in a stack of 256k. The clause now tells that case and goes on with the
rest as a program of its own run: 27 to 31 frames, however many waits.

**Every closing is done, whatever another throws.** Where a part was left — by `leave`, or by a clause that
did not go on with its rest — the first closing that threw stopped the others of that rest: an `ensuring`
around one that failed was never run (`ScenariosTest.aClosingThatFailsKeepsNoOtherFromClosing`). Closing
goes through every mark now; what the first throws is thrown on when all are closed, the others added to it.
And it cannot go round for ever on steps that are not there, as it once did on a rest without its steps.

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
