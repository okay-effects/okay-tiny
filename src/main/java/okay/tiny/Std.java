package okay.tiny;

import static okay.tiny.Fx.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The usual effects, each with the handlers that are its monads. Nothing here is of the kernel: every one is written
 * with what Fx gives, and is a pattern for an application's own.
 *
 * <p>A state, a failure, something told and something given are templates: an application says effects of its own of
 * them, each a type (see below). A choice and asynchrony have no type argument and are effects as they are.
 */
public final class Std {
  private Std() {}

  // ---- templates: an effect with a type argument, and what is made of it ------------------------------------------
  //
  // A state of S, a failure of E, a stream of T, something given of E: each is a template of effects, and an effect
  // is one of them said for a type of its own —
  //
  //   interface Count { State<Integer, Count> state = State.of(Count.class); }
  //
  // Count is an effect: it is in a row as any other, javac wants its handler, and two states of two types are two
  // types. It is asked and handled through what was made: Count.state.get(), Count.state.put(1),
  // Count.state.from(0). A template's operations are generic records, and each says what it is of (Op.of): the
  // template as it was said for Count, Count.state — the same object in the operation and in the handler made of
  // it. So the machine finds Count's handler for Count's operations, whatever other state is in force.

  // ---- a state: read and written. Its handler is the state monad ---------------------------------------------------

  /** A state of S, as the effect F. */
  public static final class State<S, F> {
    private sealed interface Asked<S, F> {}
    public record Get<S, F>(State<S, F> of) implements Asked<S, F>, Op<S, F> {}
    public record Put<S, F>(State<S, F> of, S state) implements Asked<S, F>, Op<Void, F> {}

    private final Class<F> effect;

    private State(Class<F> effect) { this.effect = effect; }

    public static <S, F> State<S, F> of(Class<F> effect) { return new State<>(effect); }

    @Override public String toString() { return effect.getSimpleName(); }

    public <R extends F> Comp<S, R> get() { return perform(new Get<>(this)); }

    public <R extends F> Comp<Void, R> put(S state) { return perform(new Put<>(this, state)); }

    public <R extends F> Comp<Void, R> update(Function<S, S> f) {
      return this.<R>get().flatMap(s -> put(f.apply(s)));
    }

    /** Its handler: the part is run from this state, and gives its value with the state it ended in. */
    public <A, R> Handler<F, A, Final<S, A>, R> from(S initial) {
      return by(this, initial, Final::new, (Asked<S, F> op) -> switch (op) {
        case Get<S, F> get -> on(get, (s, k) -> k.apply(s, s));
        case Put<S, F> put -> on(put, (s, k) -> k.apply(null, put.state()));
      });
    }
  }

  /** What a part gives under a state: its value, and the state it ended with. */
  public record Final<S, A>(S state, A value) {}

  // ---- a failure: the part does not go on. Its handlers are the option and the either monads -----------------------

  /** A failure of E, as the effect F. */
  public static final class Fail<E, F> {
    public record Raise<E, F>(Fail<E, F> of, E error) implements Op<Void, F> {}

    private final Class<F> effect;

    private Fail(Class<F> effect) { this.effect = effect; }

    public static <E, F> Fail<E, F> of(Class<F> effect) { return new Fail<>(effect); }

    @Override public String toString() { return effect.getSimpleName(); }

    /** Of any type: its answer never comes. */
    public <T, R extends F> Comp<T, R> with(E error) {
      return Fx.<Void, F, R>perform(new Raise<>(this, error)).map(never -> null);
    }

    public <A, R> Handler<F, A, Optional<A>, R> option() {
      return by(this, null, (s, a) -> Optional.of(a), (Raise<E, F> raise) -> leave(Optional.empty()));
    }

    public <A, R> Handler<F, A, Result<E, A>, R> result() {
      return by(this, null, (s, a) -> new Result.Ok<>(a),
          (Raise<E, F> raise) -> leave(new Result.Failed<>(raise.error())));
    }
  }

  public sealed interface Result<E, A> {
    record Ok<E, A>(A value) implements Result<E, A> {}
    record Failed<E, A>(E error) implements Result<E, A> {}
  }

  // ---- something told on the way. Its handler is the writer monad --------------------------------------------------

  /** A stream of T, as the effect F. */
  public static final class Emit<T, F> {
    public record Tell<T, F>(Emit<T, F> of, T item) implements Op<Void, F> {}

    private final Class<F> effect;

    private Emit(Class<F> effect) { this.effect = effect; }

    public static <T, F> Emit<T, F> of(Class<F> effect) { return new Emit<>(effect); }

    @Override public String toString() { return effect.getSimpleName(); }

    public <R extends F> Comp<Void, R> tell(T item) { return perform(new Tell<>(this, item)); }

    /** Its handler: the part's value, with what it told. */
    public <A, R> Handler<F, A, Told<T, A>, R> told() {
      return by(this, List.<T>of(), Told::new,
          (Tell<T, F> tell) -> on(tell, (items, k) -> k.apply(null, plus(items, tell.item()))));
    }
  }

  /** What a part gives with what it told. */
  public record Told<T, A>(List<T> items, A value) {}

  // ---- something given from outside. Its handler is the reader monad -----------------------------------------------

  /** Something given, an E, as the effect F. */
  public static final class Env<E, F> {
    public record Ask<E, F>(Env<E, F> of) implements Op<E, F> {}

    private final Class<F> effect;

    private Env(Class<F> effect) { this.effect = effect; }

    public static <E, F> Env<E, F> of(Class<F> effect) { return new Env<>(effect); }

    @Override public String toString() { return effect.getSimpleName(); }

    public <R extends F> Comp<E, R> ask() { return perform(new Ask<>(this)); }

    /** Its handler: every ask is answered with this. */
    public <A, R> Handler<F, A, A, R> given(E value) {
      return by(this, null, (none, a) -> a, (Ask<E, F> ask) -> answer(ask, value));
    }
  }

  // ---- a choice: the rest is gone on with for each. Its handler is the list monad ----------------------------------

  public sealed interface Choice {
    record Of<X>(List<X> options) implements Choice, Op<X, Choice> {}
  }

  public static <X, R extends Choice> Comp<X, R> choose(List<X> options) { return perform(new Choice.Of<>(options)); }

  /** Every way: what the part gives for each option, in their order. */
  public static <A, R> Handler<Choice, A, List<A>, R> all() {
    return folding(Choice.class, null, (s, a) -> plus(List.of(), a), op -> switch (op) {
      case Choice.Of<?> of -> each(of);
    });
  }

  private static <X, S, A, R> Case<Choice, S, List<A>, R> each(Choice.Of<X> of) {
    return on(of, (s, k) -> {
      Comp<List<A>, R> ways = pure(List.of());
      for (X option : of.options()) {
        Comp<List<A>, R> before = ways;
        ways = before.flatMap(found -> k.apply(option, s).map(more -> both(found, more)));
      }
      return ways;
    });
  }

  /**
   * Work that ends later. Many at once is one operation that holds a list: its handler starts them all before it
   * waits for any. The answer is the stages, done: `await` reads them, so a failure is thrown in the program, where
   * what was to be closed is.
   */
  public sealed interface Async {
    record Await<X>(List<Supplier<CompletionStage<X>>> starts)
        implements Async, Op<List<CompletableFuture<X>>, Async> {}
  }

  /** The work is started when this is asked, not when the program is made. */
  public static <X, R extends Async> Comp<X, R> await(Supplier<CompletionStage<X>> start) {
    return Std.<X, R>awaitAll(List.of(start)).map(all -> all.get(0));
  }

  /** All are started, then all waited for: what they gave, in their order; or the first failure, in that order. */
  public static <X, R extends Async> Comp<List<X>, R> awaitAll(List<Supplier<CompletionStage<X>>> starts) {
    return perform(new Async.Await<>(List.copyOf(starts))).map(done -> {
      List<X> given = new ArrayList<>();
      for (CompletableFuture<X> one : done) given.add(got(one));
      return Collections.unmodifiableList(given);
    });
  }

  /** One meaning: the thread that runs the program waits. A handler like any other, anywhere among the others. */
  public static <B, R> Handler<Async, B, B, R> blocking() {
    return handling(Async.class, op -> switch (op) {
      case Async.Await<?> await -> waited(await);
    });
  }

  private static <X, B, R> Case<Async, Void, B, R> waited(Async.Await<X> await) {
    List<CompletableFuture<X>> started = started(await);
    all(started).join();
    return answer(await, started);
  }

  /**
   * The other: no thread waits. The program is run to where it first has to wait; its rest goes on, each time, on
   * the thread that ends the work waited for. Only `Async` may be left in the row: the other handlers are in the
   * program, and go on with it. A failure, of the program or of the work, is the stage's.
   */
  public static <A> CompletableFuture<A> start(Comp<A, Async> program) { return start(program, null); }

  /** With an observer, as `run(program, see)`. */
  public static <A> CompletableFuture<A> start(Comp<A, Async> program, Consumer<? super Op<?, ?>> see) {
    Handler<Async, A, CompletableFuture<A>, Object> later =
        folding(Async.class, null, (s, a) -> CompletableFuture.completedFuture(a), op -> switch (op) {
          case Async.Await<?> await -> later(await, see);
        });
    try {
      return run(Fx.<CompletableFuture<A>>top().handler(later).handle(program), see);
    } catch (Throwable thrown) {
      return CompletableFuture.failedFuture(thrown);
    }
  }

  private static <X, A> Case<Async, Void, CompletableFuture<A>, Object> later(Async.Await<X> await,
                                                                              Consumer<? super Op<?, ?>> see) {
    return keep(await, (s, k) -> {
      List<CompletableFuture<X>> started = started(await);
      CompletableFuture<Void> all = all(started);
      if (all.isDone()) return k.apply(started, null);            // nothing to wait for: it goes on where it is
      return pure(all.thenCompose(done -> run(k.apply(started, null), see)));     // the rest, in a run of its own
    });
  }

  /** Each started; one that throws where it starts is a stage that failed. */
  private static <X> List<CompletableFuture<X>> started(Async.Await<X> await) {
    List<CompletableFuture<X>> started = new ArrayList<>();
    for (Supplier<CompletionStage<X>> start : await.starts()) {
      try {
        started.add(start.get().toCompletableFuture());
      } catch (Throwable thrown) {
        started.add(CompletableFuture.failedFuture(thrown));
      }
    }
    return started;
  }

  /** Done when each is, whatever it ended with. */
  private static CompletableFuture<Void> all(List<? extends CompletableFuture<?>> stages) {
    return CompletableFuture.allOf(stages.toArray(new CompletableFuture<?>[0])).handle((done, failed) -> null);
  }

  /** What a stage that is done gave; its failure thrown as it was, where it is not a checked one. */
  private static <X> X got(CompletableFuture<X> done) {
    try {
      return done.join();
    } catch (CompletionException wrapped) {
      if (wrapped.getCause() instanceof RuntimeException failure) throw failure;
      if (wrapped.getCause() instanceof Error failure) throw failure;
      throw wrapped;
    }
  }

  // ---- the usual shapes of a program: what a loop and an `if` are in a chain. Each is a program, and nothing of
  // its body is run when it is made. The row of a body may be less than the whole's: `? super R`.

  /** The body for each item, in their order. */
  public static <X, R> Comp<Void, R> forEach(Iterable<X> items,
                                             Function<? super X, ? extends Comp<?, ? super R>> body) {
    return Std.<X, Void, R>fold(items, null, (none, item) -> Std.<Object, R>of(body.apply(item)).map(done -> null));
  }

  /** What the body gives for each item, in their order. */
  public static <X, Y, R> Comp<List<Y>, R> collect(Iterable<X> items,
                                                   Function<? super X, ? extends Comp<Y, ? super R>> body) {
    return Std.<X, Link<Y>, R>fold(items, null,
        (given, item) -> Std.<Y, R>of(body.apply(item)).map(y -> new Link<>(y, given))).map(given -> {
          List<Y> all = new ArrayList<>();
          for (Link<Y> link = given; link != null; link = link.before()) all.add(link.last());
          Collections.reverse(all);
          return Collections.unmodifiableList(all);
        });
  }

  /** A state carried through the items: each step gives the next one. */
  public static <X, S, R> Comp<S, R> fold(Iterable<X> items, S initial,
                                          BiFunction<? super S, ? super X, ? extends Comp<S, ? super R>> step) {
    List<X> all = new ArrayList<>();                    // a list by index, not an iterator: a rest gone on with
    items.forEach(all::add);                            // twice goes on from the same item twice
    return Fx.<S, R>pure(initial).flatMap(state -> from(all, 0, state, step));
  }

  private static <X, S, R> Comp<S, R> from(List<X> items, int at, S state,
                                           BiFunction<? super S, ? super X, ? extends Comp<S, ? super R>> step) {
    return at == items.size() ? pure(state)
        : Std.<S, R>of(step.apply(state, items.get(at))).flatMap(next -> from(items, at + 1, next, step));
  }

  /** A loop with no list: the step again while the state says so. */
  public static <S, R> Comp<S, R> iterate(S initial, Predicate<? super S> goesOn,
                                          Function<? super S, ? extends Comp<S, ? super R>> step) {
    return Fx.<S, R>pure(initial).flatMap(state -> !goesOn.test(state) ? pure(state)
        : Std.<S, R>of(step.apply(state)).flatMap(next -> iterate(next, goesOn, step)));
  }

  /** The program, where the condition holds; its value is not needed. */
  public static <R> Comp<Void, R> when(boolean condition, Comp<?, ? super R> then) {
    return condition ? Std.<Object, R>of(then).map(done -> null) : pure(null);
  }

  /** One, then the other, and a value of both. */
  public static <A, B, C, R> Comp<C, R> zip(Comp<A, ? super R> one, Comp<B, ? super R> other,
                                            BiFunction<? super A, ? super B, ? extends C> both) {
    return Std.<A, R>of(one).flatMap(a -> Std.<B, R>of(other).map(b -> both.apply(a, b)));
  }

  /** What was given so far, the last first: a value, so each way of a rest gone on with twice has its own. */
  private record Link<Y>(Y last, Link<Y> before) {}

  /** A program of fewer effects is one of more as it is: the row is a phantom. */
  @SuppressWarnings("unchecked")
  private static <A, R> Comp<A, R> of(Comp<? extends A, ? super R> program) { return (Comp<A, R>) program; }

  // ----

  private static <X> List<X> plus(List<X> items, X item) {
    List<X> more = new ArrayList<>(items);
    more.add(item);
    return Collections.unmodifiableList(more);
  }

  private static <X> List<X> both(List<X> items, List<X> others) {
    List<X> more = new ArrayList<>(items);
    more.addAll(others);
    return Collections.unmodifiableList(more);
  }
}
