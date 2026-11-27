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
 * <p>A state, a failure, something told and something given are prototypes: an application makes effects of its
 * own of them, each a type (see below). A choice and asynchrony have no type argument and are effects as they are.
 */
public final class Std {
  private Std() {}

  // ---- prototypes: an effect with a type argument, and effects of their own made of it ----------------------------
  //
  // The machine finds a handler by the class of its effect, and a class has no type argument: a State<Integer> and
  // a State<String> would be one effect to it. So the four below are not effects but prototypes of effects: the
  // operations, generic, and what handles them. An effect is made of one by saying its type — a type of its own,
  // for the row — and one record that holds the prototype's operations:
  //
  //   sealed interface Count {
  //     record Ask<X>(State<Integer, X> op) implements Count, State.Of<Integer, X, Count> {}
  //     State.In<Integer, Count> state = State.in(Ask::new);
  //   }
  //
  // and it is asked and handled through that: Count.state.get(), Count.state.put(1), Count.state.from(0). Count is
  // an effect like any written by hand: javac wants its handler, two states of two types are two types in a row,
  // and a handler of one never sees an operation of the other.

  /** What the one record of an effect made of a prototype implements: it holds an operation P, whose answer is X. */
  public interface Of<P, X, F> extends Op<X, F> { P op(); }

  /** The class of an effect's one record: all its operations are of it, so its handler is found by it. */
  @SuppressWarnings("unchecked")
  private static <F> Class<F> effect(Op<?, F> anOperation) { return (Class<F>) anOperation.getClass(); }

  // ---- a state: read and written. Its handler is the state monad ---------------------------------------------------

  /** An operation of a state of S, whose answer is X. */
  public sealed interface State<S, X> {
    /** What a state does with this operation: here its answer's type is known, which a switch would not tell. */
    <F, B, R> Case<F, S, B, R> clause(Op<X, F> asked);

    record Get<S>() implements State<S, S> {
      @Override public <F, B, R> Case<F, S, B, R> clause(Op<S, F> asked) { return on(asked, (s, k) -> k.apply(s, s)); }
    }

    record Put<S>(S state) implements State<S, Void> {
      @Override public <F, B, R> Case<F, S, B, R> clause(Op<Void, F> asked) {
        return on(asked, (s, k) -> k.apply(null, state));
      }
    }

    interface Of<S, X, F> extends Std.Of<State<S, X>, X, F> {}

    /** How an effect F is made of a state of S: its record's constructor, `Ask::new`, for every answer X. */
    interface Made<S, F> { <X> Of<S, X, F> of(State<S, X> op); }

    static <S, F> In<S, F> in(Made<S, F> made) { return new In<>(made); }

    /** A state of S as the effect F. */
    final class In<S, F> {
      private final Made<S, F> made;
      private final Class<F> effect;

      private In(Made<S, F> made) {
        this.made = made;
        this.effect = effect(made.of(new Get<S>()));
      }

      public <R extends F> Comp<S, R> get() { return perform(made.of(new Get<>())); }

      public <R extends F> Comp<Void, R> put(S state) { return perform(made.of(new Put<>(state))); }

      public <R extends F> Comp<Void, R> update(Function<S, S> f) {
        return this.<R>get().flatMap(s -> put(f.apply(s)));
      }

      /** Its handler: the part is run from this state, and gives its value with the state it ended in. */
      public <A, R> Handler<F, A, Final<S, A>, R> from(S initial) {
        return folding(effect, initial, Final::new, op -> clause(held(op)));
      }

      /** An operation of F is its record, which holds a state's: the handler was found by that record's class. */
      @SuppressWarnings("unchecked")
      private Of<S, ?, F> held(F op) { return (Of<S, ?, F>) op; }

      private static <S, X, F, B, R> Case<F, S, B, R> clause(Of<S, X, F> asked) { return asked.op().clause(asked); }
    }
  }

  /** What a part gives under a state: its value, and the state it ended with. */
  public record Final<S, A>(S state, A value) {}

  // ---- a failure: the part does not go on. Its handlers are the option and the either monads -----------------------

  /** An operation of a failure of E. */
  public sealed interface Fail<E, X> {
    E error();

    record Raise<E>(E error) implements Fail<E, Void> {}

    interface Of<E, X, F> extends Std.Of<Fail<E, X>, X, F> {}

    interface Made<E, F> { <X> Of<E, X, F> of(Fail<E, X> op); }

    static <E, F> In<E, F> in(Made<E, F> made) { return new In<>(made); }

    /** A failure of E as the effect F. */
    final class In<E, F> {
      private final Made<E, F> made;
      private final Class<F> effect;

      private In(Made<E, F> made) {
        this.made = made;
        this.effect = effect(made.of(new Raise<E>(null)));
      }

      /** Of any type: its answer never comes. */
      public <T, R extends F> Comp<T, R> with(E error) {
        return Fx.<Void, F, R>perform(made.of(new Raise<>(error))).map(never -> null);
      }

      public <A, R> Handler<F, A, Optional<A>, R> option() {
        return folding(effect, null, (s, a) -> Optional.of(a), op -> leave(Optional.empty()));
      }

      public <A, R> Handler<F, A, Result<E, A>, R> result() {
        return folding(effect, null, (s, a) -> new Result.Ok<>(a),
            op -> leave(new Result.Failed<>(held(op).op().error())));
      }

      @SuppressWarnings("unchecked")
      private Of<E, ?, F> held(F op) { return (Of<E, ?, F>) op; }
    }
  }

  public sealed interface Result<E, A> {
    record Ok<E, A>(A value) implements Result<E, A> {}
    record Failed<E, A>(E error) implements Result<E, A> {}
  }

  // ---- something told on the way. Its handler is the writer monad --------------------------------------------------

  /** An operation of a stream of T. */
  public sealed interface Emit<T, X> {
    T item();

    record Tell<T>(T item) implements Emit<T, Void> {}

    interface Of<T, X, F> extends Std.Of<Emit<T, X>, X, F> {}

    interface Made<T, F> { <X> Of<T, X, F> of(Emit<T, X> op); }

    static <T, F> In<T, F> in(Made<T, F> made) { return new In<>(made); }

    /** A stream of T as the effect F. */
    final class In<T, F> {
      private final Made<T, F> made;
      private final Class<F> effect;

      private In(Made<T, F> made) {
        this.made = made;
        this.effect = effect(made.of(new Tell<T>(null)));
      }

      public <R extends F> Comp<Void, R> tell(T item) { return perform(made.of(new Tell<>(item))); }

      /** Its handler: the part's value, with what it told. */
      public <A, R> Handler<F, A, Told<T, A>, R> told() {
        return folding(effect, List.<T>of(), Told::new, op -> telling(held(op)));
      }

      @SuppressWarnings("unchecked")
      private Of<T, ?, F> held(F op) { return (Of<T, ?, F>) op; }

      private static <T, X, F, B, R> Case<F, List<T>, B, R> telling(Of<T, X, F> asked) {
        return on(asked, (items, k) -> k.apply(null, plus(items, asked.op().item())));
      }
    }
  }

  /** What a part gives with what it told. */
  public record Told<T, A>(List<T> items, A value) {}

  // ---- something given from outside. Its handler is the reader monad -----------------------------------------------

  /** An operation of something given, an E, whose answer is X. */
  public sealed interface Env<E, X> {
    /** Answered with what is given: here the answer's type is known to be E. */
    <F, S, B, R> Case<F, S, B, R> given(Op<X, F> asked, E value);

    record Ask<E>() implements Env<E, E> {
      @Override public <F, S, B, R> Case<F, S, B, R> given(Op<E, F> asked, E value) { return answer(asked, value); }
    }

    interface Of<E, X, F> extends Std.Of<Env<E, X>, X, F> {}

    interface Made<E, F> { <X> Of<E, X, F> of(Env<E, X> op); }

    static <E, F> In<E, F> in(Made<E, F> made) { return new In<>(made); }

    /** Something given, an E, as the effect F. */
    final class In<E, F> {
      private final Made<E, F> made;
      private final Class<F> effect;

      private In(Made<E, F> made) {
        this.made = made;
        this.effect = effect(made.of(new Ask<E>()));
      }

      public <R extends F> Comp<E, R> ask() { return perform(made.of(new Ask<>())); }

      /** Its handler: every ask is answered with this. */
      public <A, R> Handler<F, A, A, R> given(E value) {
        return handling(effect, op -> answering(held(op), value));
      }

      @SuppressWarnings("unchecked")
      private Of<E, ?, F> held(F op) { return (Of<E, ?, F>) op; }

      private static <E, X, F, B, R> Case<F, Void, B, R> answering(Of<E, X, F> asked, E value) {
        return asked.op().given(asked, value);
      }
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

  /** The class of an effect with type arguments, as the class of that effect of those arguments. */
  @SuppressWarnings("unchecked")
  private static <F> Class<F> of(Class<?> effect) { return (Class<F>) effect; }

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
