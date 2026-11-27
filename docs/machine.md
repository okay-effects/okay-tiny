# The machine

How `Fx.run` works, for someone reviewing `Fx.java`. It is one loop of about ninety lines; the rest of the file
is types.

## A program is a tree

Seven records, of which a user makes five:

| Node | Made by | Means |
|---|---|---|
| `Pure(value)` | `pure` | a value |
| `Perform(op)` | `perform` | ask an operation |
| `FlatMap(first, next)` | `flatMap` | `first`, then the program `next` makes of its value |
| `Mapped(first, f)` | `map` | `first`, then the value `f` makes of its value |
| `Ensure(part, after)` | `ensuring` | `part`, and `after` when it ends |
| `Part(handler, part)` | `row()…handle` | `part` with a handler around it |
| `Resume(k, value, state)` | `k.apply` | go on with a rest that was taken |

Making a node does nothing. `R`, the row, is only in the types: nothing of it is in a node.

## What the loop holds

| Variable | Is |
|---|---|
| `now` | the node being run |
| `rest` | the steps of the part being run: what to do with the value of `now`, innermost first |
| `around` | the parts being run, innermost first: for each its handler, that handler's state, and the steps after it |
| `asked` | the operation asked last, for `Fx.Where` |

`rest` and `around` are linked lists. The steps are never changed; a part's state moves on where it is, in the
part's own record. That is what lets a rest be a value: to take it is to keep two pointers.

A step is one of four, a sealed type: `Then` (of `flatMap`: a program of the value so far), `Mapping` (of `map`: a
value of it), and two marks the value passes — `Closing` (of `ensuring`: the part it was for has ended) and
`Taken` (under a clause: the rest that clause was given).

## One turn

The loop looks at `now`:

- **`Pure`** — there is a value. Take the next step from `rest`.
- **`FlatMap`, `Mapped`** — put the function on `rest`, go into `first`.
- **`Asking`** — `perform(op).flatMap(next)`, as one node: as `Perform`, and where the operation is answered in
  place its answer is given to `next` at once, with no step put.
- **`Ensure`** — put a mark on `rest` that holds `after`, go into the part.
- **`Part`** — put a record on `around` (the handler, and the current `rest` as the steps after the part), start
  the part with an empty `rest` and the handler's initial state.
- **`Perform`** — find the nearest part whose handler's effect the operation is of, ask its `cases` for the
  clause. If the clause is an `answer`, there is a value at once and nothing moves. Otherwise the rest is
  taken (below).
- **`Resume`** — put a rest back (below).

Then, with a value: while `rest` is empty, a part has ended — apply its handler's `ret` to its state and the
value, and continue with the steps after it. If there is no part left, the value is the result. Otherwise take
a step: a mapped step makes a value and the loop takes the next; a `flatMap` step makes a program, which becomes
`now`.

No call of the loop is ever nested in another. A program of a million steps uses a constant amount of the Java
stack; its depth is in `rest`.

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

`k.apply(answer, next)` makes a `Resume` node. When the loop reaches it:

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

It may be written in place because a part that is run is the loop's alone. A rest taken off takes its parts
with it, and the loop goes on with those around; a rest gone on with is given parts made anew. Only a rest that
may be *kept* while its parts go on being run (`keep`) takes parts of its own at once
(`ScenariosTest.theStatesOfARestTakenOffAreItsOwn`, `partsDeepOneInAnotherKeepTheirStatesOnEveryWay`).

## What to check when reading it

- No field of a node or a step is ever written after it is made. Two things are: the state of a part that is
  being run, and `K.gone`, a flag that only ever goes from false to true.
- A part a rest holds is never run: the rest goes on with parts made anew of it.
- The seven places marked unchecked: the phantom row in `flatMap` and in `map`; the handlers put so far, kept as
  a function of a program of any type; the result of `handle`; the loop; and the two that open a hidden type for
  it — a part's handler with its state's type (`Around.handler`) and a clause with its answer's (`erased`). The loop is where the types that
  `Case`, `Handler` and the nodes hide are opened: its registers — `value`, and a part as the loop holds it — hold what each
  step, in its turn, has a type for, and a variable of a Java loop has one type. Nothing else is erased: the
  nodes, a clause (`Case` over its four kinds), a handler (`Handler` over `Fold`) and a step are typed. What guards the
  loop is `on` (the answer type is the operation's), the two sealed types, and the test that a clause was made
  for the operation asked.
