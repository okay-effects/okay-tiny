package okay.tiny;

import static okay.tiny.Fx.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The usual effects, each with the handlers that are its monads. Nothing here is of the kernel: every one is written
 * with what Fx gives, and is a pattern for an application's own.
 *
 * <p>An effect with a type argument is one effect whatever the argument: a handler is found by the effect's class.
 * Two states of two types in one program are told apart by where their handlers are put, not by their types.
 */
public final class Std {
  private Std() {}

  // ---- a state: read and written. Its handler is the state monad ---------------------------------------------------

  public sealed interface State<S> {
    record Get<S>() implements State<S>, Op<S, State<S>> {}
    record Put<S>(S state) implements State<S>, Op<Void, State<S>> {}
  }

  public static <S, R extends State<S>> Comp<S, R> get() { return perform(new State.Get<S>()); }

  public static <S, R extends State<S>> Comp<Void, R> put(S state) { return perform(new State.Put<>(state)); }

  public static <S, R extends State<S>> Comp<Void, R> update(Function<S, S> f) {
    return Std.<S, R>get().flatMap(s -> put(f.apply(s)));
  }

  /** What a part gives under a state: its value, and the state it ended with. */
  public record Final<S, A>(S state, A value) {}

  public static <S, A, R> Handler<State<S>, A, Final<S, A>, R> state(S initial) {
    return folding(Std.<State<S>>of(State.class), initial, Final::new, op -> switch (op) {
      case State.Get<S> get -> on(get, (s, k) -> k.apply(s, s));
      case State.Put<S> put -> on(put, (s, k) -> k.apply(null, put.state()));
    });
  }

  // ---- a failure: the part does not go on. Its handlers are the option and the either monads -----------------------

  public sealed interface Fail<E> {
    record Raise<E>(E error) implements Fail<E>, Op<Void, Fail<E>> {}
  }

  /** Of any type: its answer never comes. */
  public static <E, T, R extends Fail<E>> Comp<T, R> fail(E error) {
    return perform(new Fail.Raise<>(error)).map(never -> null);
  }

  /** `errors` only names the type of the failures, which javac cannot infer where the handler is put. */
  public static <E, A, R> Handler<Fail<E>, A, Optional<A>, R> option(Class<E> errors) { return option(); }

  public static <E, A, R> Handler<Fail<E>, A, Optional<A>, R> option() {
    return folding(Std.<Fail<E>>of(Fail.class), null, (s, a) -> Optional.of(a), op -> leave(Optional.empty()));
  }

  public sealed interface Result<E, A> {
    record Ok<E, A>(A value) implements Result<E, A> {}
    record Failed<E, A>(E error) implements Result<E, A> {}
  }

  public static <E, A, R> Handler<Fail<E>, A, Result<E, A>, R> result(Class<E> errors) { return result(); }

  public static <E, A, R> Handler<Fail<E>, A, Result<E, A>, R> result() {
    return folding(Std.<Fail<E>>of(Fail.class), null, (s, a) -> new Result.Ok<>(a), op -> switch (op) {
      case Fail.Raise<E> raise -> on(raise, (s, k) -> pure(new Result.Failed<>(raise.error())));
    });
  }

  // ---- something told on the way. Its handler is the writer monad --------------------------------------------------

  public sealed interface Emit<X> {
    record Tell<X>(X item) implements Emit<X>, Op<Void, Emit<X>> {}
  }

  public static <X, R extends Emit<X>> Comp<Void, R> tell(X item) { return perform(new Emit.Tell<>(item)); }

  /** What a part gives with what it told. */
  public record Told<X, A>(List<X> items, A value) {}

  /** `items` only names the type of what is told. */
  public static <X, A, R> Handler<Emit<X>, A, Told<X, A>, R> told(Class<X> items) { return told(); }

  public static <X, A, R> Handler<Emit<X>, A, Told<X, A>, R> told() {
    return folding(Std.<Emit<X>>of(Emit.class), List.<X>of(), Told::new, op -> switch (op) {
      case Emit.Tell<X> tell -> on(tell, (items, k) -> k.apply(null, plus(items, tell.item())));
    });
  }

  // ---- something given from outside. Its handler is the reader monad -----------------------------------------------

  public sealed interface Env<E> {
    record Ask<E>() implements Env<E>, Op<E, Env<E>> {}
  }

  public static <E, R extends Env<E>> Comp<E, R> ask() { return perform(new Env.Ask<E>()); }

  public static <E, A, R> Handler<Env<E>, A, A, R> env(E value) {
    return handling(Std.<Env<E>>of(Env.class), op -> switch (op) {
      case Env.Ask<E> ask -> answer(ask, value);
    });
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
