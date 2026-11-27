# The machine

How `Fx.run` works, for someone reviewing `Fx.java`. The machine is a trampoline of typed moves: about a
hundred and seventy lines, the last part of the file; the rest of the file is types.

## A program is a tree

Nine records, of which a user makes seven:

| Node | Made by | Means |
|---|---|---|
| `Pure(value)` | `pure` | a value |
| `Perform(op)` | `perform` | ask an operation |
| `Asking(op, next)` | `perform(op).flatMap(next)` | ask an operation, then the program `next` makes of its answer |
| `FlatMap(first, next)` | `flatMap` | `first`, then the program `next` makes of its value |
| `Mapped(first, f)` | `map` | `first`, then the value `f` makes of its value |
| `Ensure(part, after)` | `ensuring` | `part`, and `after` when it ends |
| `Defer(program)` | `defer` | the program, made when it is reached |
| `Part(handler, part)` | `row()…handle` | `part` with a handler around it |
| `Resume(k, value, state)` | `k.apply` | go on with a rest that was taken |

Making a node does nothing. `R`, the row, is only in the types: nothing of it is in a node.

## What the machine is at

Three things, at any moment: a program to run, the steps that will take its value, and the parts — one for
each handler in force — those steps end in. Their types say how they fit:

| Type | Is |
|---|---|
| `Comp<A, ?>` | a program that gives an `A` |
| `Rest<A, Y>` | the steps of the part being run: from a value of `A` to the value of that part, `Y` |
| `Around<Y, Z>` | the parts being run, innermost first: the innermost gives `Y`, the whole program `Z` |

A program of `A` can be run only with steps from `A` and parts from where those steps end. That is all of the
machine's correctness, and javac checks it at every move.

A step is one of five, a sealed type `Rest<A, Z>`: `Then` (of `flatMap`: a program of the value so far),
`Mapping` (of `map`: a value of it), two marks the value passes — `Closing` (of `ensuring`: the part it was for
has ended) and `Taken` (under a clause: the rest that clause was given) — and `End`, no steps, where `A` is `Z`.
A part is `In<F, S, A, B, R, Y, Z>`: its handler `Fold<F, S, A, B, R>` and that handler's state `S`, together;
the steps after the part, `Rest<B, Y>` — they take what the handler gives; and the part around it,
`Around<Y, Z>`. `Top` is no part, where `A` is `Z`.

Java's `switch` does not learn from a case that two types are one. So `End` and `Top` say it by a method that
only they have: `Rest.end(value)` gives the value as the part's, `Around.result(value)` as the program's.

## A trampoline

What the machine is at is itself a value of an algebraic type, `At<Z>`:

| | |
|---|---|
| `Run<A, Y, Z>(now, rest, around)` | a program to run |
| `Give<A, Y, Z>(value, rest, around)` | a value for the steps |
| `Done<Z>(result)` | the program has ended |

`A` and `Y` are hidden in it, as the answer's type is in a `Case`. A move takes one and gives the next, and
one loop does that until the next is `Done`:

```java
while (!(at instanceof Done<Z> done)) at = at.next(this);
```

No move is left on the stack when the next begins: a program of a million steps takes a constant amount of
the Java stack, and its depth is in `rest`. Three things keep a move from costing an object each time:

- A `Run` moves on in place where only its program changes — an operation answered in place, `defer`. The
  steps, the parts and `A` are the same, so it is typed as it stands.
- A value passes up to `STEPS` (four) steps within one move, and a move goes up to four nodes down into a
  program, before it gives the next its turn. The number is a constant: the stack a move takes is bounded.

## One move

`run` looks at the node:

- **`Pure`** — there is a value: give it to the steps.
- **`FlatMap`, `Mapped`** — put the function on the steps, go into `first`.
- **`Perform`, `Asking`** — find the nearest part whose handler's effect the operation is of (the effect it
  says, `of()`, where it says one; else the one its class is of), ask its `cases`
  for the clause. If it is an `answer`, there is a value at once and nothing moves: `Perform` gives it to the
  steps, `Asking` runs the program `next` makes of it. Otherwise the rest is taken (below).
- **`Ensure`** — put a mark on the steps that holds `after`, go into the part.
- **`Part`** — a new part: the handler, its initial state, the steps so far as the steps after the part; the
  part's program is run with no steps yet.
- **`Resume`** — put a rest back (below).
- **`Defer`** — make the program, run it.

`give` looks at the steps: a `Then` makes a program, which is run; a `Mapping` makes a value, which is given
on; a mark is passed; and at `End` a part has ended — its handler's `ret`, of its state and the value, gives
what goes to the steps after it — or, at `Top`, the program has.

## Taking the rest

When a clause needs `k`:

```
before                              the clause runs with
around:  [C] [B] [H] [A]            around:  [A]
rest:    steps of C                 rest:    a mark, then the steps after H
k = (steps of C, the parts C..H, each with its state)
```

Everything from the question to the end of the handler's own part `H` goes into `k` — two pointers. The parts
taken off are run by no one from then on: they are the rest's, with the states they had. The clause then runs as a program of what is *around* `H`: that is why it may ask the effects
that stay outside, and why its own handler is not in force inside it.

The mark holds `k`. When the clause ends and `k` was never applied, the rest was left, and whatever it was to
close is closed.

A clause that gives nothing but its own rest gone on with — `k.apply(answer, next)` — is seen at once: nothing
is taken off, the state is set to `next`, and `answer` goes to the steps that were there. Only another answer
of the clause — a program of its own, the rest gone on with later or twice, the part left — takes the rest off.

## Putting it back

`k.apply(answer, next)` makes a `Resume` node. When the machine reaches it:

```
around now:  [A']                   around after:  [C'] [B'] [H'] [A']
                                    states:        C', B' as C, B were when asked; H' = next; A' as it is now
```

The parts go back on top of what is run *now*, not of what was run then. So a handler outside (`A`) keeps
whatever happened to it while the clause ran, and the parts inside come back exactly as they were — which is
what makes a second `k.apply` a second, independent run.

The parts put back are made anew of those taken off, so that the ones the rest holds are never run and never
change: a rest gone on with twice goes on with two sets of parts.

## Where the state lives

With its handler: a part's record holds the handler and that handler's state, of one type. A new state — a
clause that goes on with `k.apply(answer, next)` — is written there, whichever part it is: the innermost and the
outermost cost the same.

It may be written in place because a part that is run is the machine's alone. A rest taken off takes its parts
with it, and the machine goes on with those around; a rest gone on with is given parts made anew. Only a rest that
may be *kept* while its parts go on being run (`keep`) takes parts of its own at once
(`ScenariosTest.theStatesOfARestTakenOffAreItsOwn`, `partsDeepOneInAnotherKeepTheirStatesOnEveryWay`).

## What to check when reading it

- No field of a node or a step is ever written after it is made. Two things are: the state of a part that is
  being run, and `K.gone`, a flag that only ever goes from false to true.
- A part a rest holds is never run: the rest goes on with parts made anew of it.
- When a program fails, what was to be closed is found from the `At` the failing move began with — or, for a
  closing that itself fails, the one after it, so that it is not run twice.
- The places marked unchecked, and what guards each. Three are a hidden type opened by an identity: the answer
  of a case, and the clause of one, are of the operation asked because the case was made for that very
  operation (`made`); what a clause gave is its own rest gone on with because it is that very object
  (`K.only`). One is a part made anew as the part it replaces (`K.its`). One is an operation as one of its
  handler's effect, which the machine has just tested (`Fold.of`). Two are the one `End` and the one `Top`, of
  any type. The rest are outside the machine: the phantom row in `flatMap` and `map`, and the handlers being
  put, kept as a function of a program of any type.
