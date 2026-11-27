# Direct style

A program written as a chain of `flatMap` is exact, and tiring. `okay-tiny-direct` is a plugin of javac that lets a
method be written as plain Java, and rewrites it into the chain before javac checks its types. Nothing else
changes: the same effects, the same handlers, the same machine, no thread and no library at run time beyond one
small class. Every example here is compiled by the plugin and run: `direct/src/test` (`Programs`, `DirectTest`).

```java
@Direct
static <R extends Stock & Refusal> Comp<Order, R> place(List<Line> lines) {
  if (lines.isEmpty()) $(perform(new Refusal.Invalid("an empty cart")));
  for (Line line : lines) {
    boolean reserved = $(perform(new Stock.Reserve(line.sku(), line.qty())));
    if (!reserved) $(perform(new Refusal.Invalid(line.sku())));
  }
  List<Integer> prices = $(collect(lines, line -> perform(new Stock.Price(line.sku()))));
  int total = 0;
  for (int i = 0; i < lines.size(); i++) total += prices.get(i) * lines.get(i).qty();
  return pure(new Order(lines.stream().map(Line::sku).toList(), total));
}
```

Two words:

- **`$(program)`** runs a program here and is its value.
- **`return program`** is what the method ends with: `return pure(value)`, or another method's program —
  `return odd(n - 1)` — which is then a call in the tail, and takes neither stack nor heap.

The method's type is what it was: `Comp<Order, R>`, with its row. It is called, handled and run like any
other program.

## What you get

- **The row is checked by javac**, of the chain the plugin wrote: an effect the row has not is an error.
- **Types are inferred** where a chain has to be told them: `List<Integer> prices = $(collect(lines, ..));`
  needs no `Std.<Line, Integer, R>collect(..)`. The rewritten code gives javac both programs as arguments
  (`Do.bind`), and it infers the first from what the second takes.
- **Nothing is run when the program is made**: the whole body is put off (`defer`), so `place(lines)` is a
  value, and running it twice runs the body twice.
- **The rest after a `$` is a value**, as after any operation: a handler may leave it (nothing after the `$` is
  run), or go on with it twice — each way with its own variables (`DirectTest.everyWay`).
- **`try { .. } finally { .. }` is `ensuring`**: closed at the end, when the part is left at a `$`, under an
  exception.

## What is rewritten

| Written | Becomes |
|---|---|
| `T x = $(p); rest` | `bind(p, (T x) -> { rest })` |
| `$(p); rest` | `bind(p, ignored -> { rest })` |
| `f($(p), $(q))` in any statement | `bind(p, a -> bind(q, b -> { .. f(a, b) .. }))`: every `$` of a statement first, in the order written |
| `if (c) { .. $ .. } else { .. } rest` | the `if` as a program, then `rest`; where a branch has a `return`, `rest` is a part of each branch |
| `for (T x : items) { .. $ .. } rest` | `bind(forEach(items, (T x) -> { .. }), ignored -> { rest })` |
| `try { .. $ .. } finally { f } rest` | `bind(ensuring(.., () -> { f }), ignored -> { rest })` |
| the whole body | `return defer(() -> { .. });` |

A statement with no `$` in it is left as it is — a loop, a `switch`, a `return` inside an `if`.

## What is refused

The plugin never leaves a `$` to fail when the program runs: where it cannot rewrite one, it is an error at
that line (`direct/src/test/negative`, checked by `RefusedTest`).

| A `$` | Why | Write |
|---|---|---|
| in a lambda, or a class inside the method | it is not a step of this method | a `@Direct` method of its own; `collect`, `forEach` |
| in `while`, `do`, `for (;;)`, `switch`, `synchronized` | a loop over what changes cannot be a chain | a for-each, or a method that calls itself in the tail |
| in a branch of `?:`, on the right of `&&` or <code>&#124;&#124;</code> | it would be run whatever the condition | an `if` |
| in a `try` with a `catch` or with resources; in a `finally` | an exception is not an effect; what closes asks nothing | `try`-`finally`; a `Fail` effect |
| with a `return`, `break` or `continue` in its for-each | the loop cannot be left from inside a chain | a method that calls itself |

And javac's own, of what was written:

- `local variables referenced from a lambda expression must be final or effectively final` — a variable changed
  after a `$` that was declared before it. What follows a `$` is the body of a function: a variable declared
  before it cannot change after it. Declare it after the last `$`, or carry it in a `fold`.
- `method bindIn in class Do cannot be applied to given types … inference variable R has incompatible bounds`
  — an effect the row has not, said at the `$` that asks it; the effect is among the `upper bounds` javac lists.
  It is said there because the plugin gives every step the method's row (the second argument of the `Comp` it
  returns) as a type, so a step does not infer its row from the steps around it.
- `@Direct: the method ends here with no program` — a path that ends without `return`.

## Worth knowing

- **Every `$` of a statement is run before the statement.** In `log(a()) + $(p)` the program `p` is run first,
  and then `a()`: put what must come first in a statement of its own.
- **An editor without the plugin** sees `$` as an ordinary method and the code as ordinary Java, and is content.
- **A bare block `{ .. }` with a `$`** is rewritten as if its braces were not there.
- **Called without the plugin**, `$` throws and says so.

## Using it

The plugin works on javac's own trees, which `jdk.compiler` does not export. So javac is run in a process of its
own with four packages opened — once, in the build.

```kotlin
repositories { maven("https://okay-effects.github.io/okay-tiny/maven") }

dependencies {
    implementation("io.github.okay-effects:okay-tiny-direct:0.1.0")          // brings okay-tiny
    annotationProcessor("io.github.okay-effects:okay-tiny-direct:0.1.0")     // where javac finds the plugin
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xplugin:OkayDirect")
    options.isFork = true
    options.forkOptions.jvmArgs = listOf("api", "code", "tree", "util").map {
        "--add-exports=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED"
    }
}
```

```java
import static okay.tiny.direct.Do.$;
import okay.tiny.direct.Direct;
```

With `javac` itself: `-Xplugin:OkayDirect -processorpath okay-tiny-direct.jar:okay-tiny.jar`, and each of the four
as `-J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED` (`code`, `tree`, `util`).

## How it is made

`direct/src/main/java/okay/tiny/direct/Plugin.java`, under four hundred lines. After a file is parsed and
before its types are attributed, the body of each `@Direct` method is walked statement by statement; at the
first statement with a `$`, what follows becomes the body of the function given to `bind`, and so on inward.
javac then attributes what it would have attributed had the chain been written by hand — which is why the row,
the inference and the error messages are javac's.

One thing javac asks of trees made this way: it remembers the types of arguments by their place in the source,
so every call and lambda the plugin makes is given a place no other has — that of the `$` it replaces, of the
word `if`, `for` or `try`, of a brace, of the annotation.

It depends on javac's internal classes, as Lombok does. A new JDK may ask for changes in the plugin; the
programs written with it are plain Java with one method call, and what they compile to is `flatMap`.
