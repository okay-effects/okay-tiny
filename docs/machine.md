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
| `around` | the parts being run, innermost first: for each its handler, its depth, and the steps after it |
| `state` | the state of the innermost part's handler |
| `states` | the states of the others, by depth; an array that is never changed once shared |
| `asked` | the operation asked last, for `Fx.Where` |

`rest` and `around` are immutable linked lists. That is what lets a rest be a value: to take it is to keep two
pointers.

## One turn

The loop looks at `now`:

- **`Pure`** — there is a value. Take the next step from `rest`.
- **`FlatMap`, `Mapped`** — put the function on `rest`, go into `first`.
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
k = (steps of C, the parts C..H, their states)
```

Everything from the question to the end of the handler's own part `H` goes into `k` — two pointers and the
array of states. The clause then runs as a program of what is *around* `H`: that is why it may ask the effects
that stay outside, and why its own handler is not in force inside it.

The mark holds `k`. When the clause ends and `k` was never applied, the rest was left, and whatever it was to
close is closed.

## Putting it back

`k.apply(answer, next)` makes a `Resume` node. When the loop reaches it:

```
around now:  [A']                   around after:  [C] [B] [H] [A']
                                    states:        C, B as they were when asked; H = next; A' as it is now
```

The parts go back on top of what is run *now*, not of what was run then. So a handler outside (`A`) keeps
whatever happened to it while the clause ran, and the parts inside come back exactly as they were — which is
what makes a second `k.apply` a second, independent run.

If nothing around changed and the clause only went on, the same records are reused and nothing is made.

## Where the state lives

The innermost handler's state is a variable of the loop: for it, a new state is an assignment. The others are
in `states`, by depth; a new state for one of them is one copy of that array. Entering a part moves the
innermost state into the array; leaving a part brings the next one out.

The alternative — the state in each part's record — makes a new state for an outer handler cost a new record
for every part inside it. `DESIGN.md` has the numbers.

## When it fails

Any exception leaves the loop through one `catch`. It adds `Fx.Where` to the exception, runs every `ensuring`
mark still on the stacks — and those of any rest a running clause holds and has not applied — and throws the
exception on, unchanged.

## What to check when reading it

- No field of a node, a step or a part is ever written after it is made, except `K.gone`, a flag that only
  ever goes from false to true.
- `states` is copied before every write once it has been given to a `k`.
- The six places marked unchecked: the phantom row in `flatMap` and in `map`; a clause stored without its answer type in
  `Case`; a handler stored without its state type; the result of `handle`; the loop's own values. Each is
  where Java's types end, not where the library's guarantees do: what guards them is `on` (the answer type is
  the operation's), the private constructor of `Case`, and the test that a clause was made for the operation
  asked.
