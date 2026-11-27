# Asynchrony as an effect

Work that ends later — a call over the network, a query on another thread — is an effect like any other: the
program asks, a handler says what waiting means. There are two meanings in `Std`, and the program is the same
under both. Every example here is a test: `AsyncTest`.

## Asking

```java
static <R extends Async> Comp<Integer, R> total(Supplier<CompletionStage<Integer>> a,
                                                Supplier<CompletionStage<Integer>> b,
                                                Supplier<CompletionStage<Integer>> c) {
  return Std.<Integer, R>await(a).flatMap(first ->
      Std.<Integer, R>awaitAll(List.of(b, c)).map(two -> first + two.get(0) + two.get(1)));
}
```

- `await(() -> client.sendAsync(request))` gives what the stage gives. It takes a `Supplier`: a program is a
  value, and the work is started when the operation is asked, not when the program is made.
- `awaitAll(starts)` starts them all and then waits for all: what they gave, in their order. It is the kernel's
  rule for many at once — one operation that holds a list — and the only way two things are in flight together:
  two `await` in a chain are one after the other.
- A failure of the work is thrown in the program, at the `await`, as it was (a checked one, in the
  `CompletionException` the stage gave). So what `ensuring` was to close around it is closed.

The effect has one operation, `Async.Await`, which holds the list of starts and is answered with the stages,
done. `await` and `awaitAll` read them. A program that wants a failure as a value asks `Await` itself.

## Meaning one: the thread waits

```java
int sum = run(Fx.<Integer>top().handler(blocking()).handle(total(a, b, c)));
```

`blocking()` is a handler like any other: it starts the work, joins it on the thread that runs the program, and
answers. It may stand anywhere among the other handlers. Everything bound to that thread — a transaction of
Spring, a security context — is still there after the wait. On a virtual thread this costs what a virtual
thread costs.

## Meaning two: no thread waits

```java
CompletableFuture<Integer> sum = start(total(a, b, c));
```

`start` runs the program on the calling thread to where it first has to wait, and returns. The rest of the
program goes on, each time, on the thread that ends the work waited for; the stage is done when the program is.
Work that is done already is not waited for at all: the program goes on where it is.

`start` takes a program with only `Async` left in its row — `Comp<A, Async>` — as `run` takes one with nothing
left. The other handlers are inside the program and go on with its rest, each with its state as it was:

```java
Comp<Integer, Async> counted =
    Fx.<Async, Final<Integer, Integer>>row().handler(state(COUNT, 10)).handle(countedPrice(price)).map(Final::state);
CompletableFuture<Integer> state = start(counted);
```

javac refuses a program that still asks something else (`no suitable method found for start`).

What `ensuring` was to close stays open across a wait and is closed once, when its part has ended — with a
value, or by a failure of the work, or of the program. A failure is the stage's: `start` throws nothing.
`start(program, see)` takes an observer as `run(program, see)` does, and it sees the operations after a wait
too.

## How it is made

Under `blocking()` the clause is `answer(op, ..)` after a `join`. Under `start` the handler is a fold whose
result is a `CompletableFuture`, and its one clause keeps the rest:

```java
keep(await, (s, k) -> {
  List<CompletableFuture<X>> started = started(await);
  CompletableFuture<Void> all = all(started);
  if (all.isDone()) return k.apply(started, null);                          // it goes on where it is
  return pure(all.thenCompose(done -> run(k.apply(started, null), see)));   // the rest, in a run of its own
});
```

`k` is a value, so it can be handed to a callback; and the rest of a part of the outermost handler asks nothing,
so it can be given to `run` again. `keep` is `on` for a clause that does this: the kernel then does not take
the rest for left when the clause ends, and does not close what the rest was to close. It is the kernel's one
word for asynchrony, and it is not about asynchrony — a generator or a scheduler keeps the rest the same way
(`ScenariosTest.aRestKeptIsGoneOnWithLaterInARunOfItsOwn`).

## What to know

- **After a wait under `start` the program is on another thread.** What a framework bound to the first thread
  is not there. A use case inside a transaction of Spring asks `Async` under `blocking()`.
- **The handlers' states are values and move with the rest.** Mutable things a handler captured are reached
  from whichever thread goes on: one at a time, never two at once, but not the same one.
- **There is no cancelling.** A stage `start` gave, cancelled, stops nothing: the work goes on and the rest is
  run. Nor is there a timeout: give one to the work (`orTimeout`).
- **Two programs are not run side by side.** `awaitAll` is for work; a program is one line of steps. Two
  programs at once are two `start`.
- **A rest that waits for work that never ends is never gone on with**, and what it was to close is never
  closed.
