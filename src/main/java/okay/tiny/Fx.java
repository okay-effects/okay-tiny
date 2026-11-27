package okay.tiny;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Algebraic effects on a free monad, the least of it: one file, Java 21, no threads, no reflection, no dependency. */
public final class Fx {
  private Fx() {}

  /** An operation is data: a record of a sealed interface, the effect F, that says the type of its answer, A. */
  public interface Op<A, F> {
    /**
     * What this operation is of, where it is not a record of an effect's own but a generic one, of a template of
     * effects: the template as it was said for one effect — an object, the same in the operation and in the
     * handler made of it, which is how that handler is found. None: the handler is found by the operation's class.
     */
    default Template<F> of() { return null; }
  }

  /** A template of effects, as it was said for one effect, F: what its operations say they are of. */
  public interface Template<F> {}

  /** A program is data: A is what it gives, R the effects it may ask. */
  public sealed interface Comp<A, R> {
    /** Q: the row of the whole is the row of both parts; a program of fewer effects is one of more as it is. */
    default <B, Q extends R> Comp<B, Q> flatMap(Function<? super A, ? extends Comp<B, Q>> next) {
      return new FlatMap<A, B, Q>(this, next);
    }

    default <B, Q extends R> Comp<B, Q> map(Function<? super A, ? extends B> f) {
      return new Mapped<A, B, Q>(this, f);
    }
  }

  record Pure<A, R>(A value) implements Comp<A, R> {}
  record Mapped<X, A, R>(Comp<X, ? super R> first, Function<? super X, ? extends A> f) implements Comp<A, R> {}
  /** An operation asked, and nothing after it. With flatMap it is the node below: one, not two. */
  record Perform<A, R>(Op<A, ? super R> op) implements Comp<A, R>, Asks<A, A> {
    @Override public <B, Q extends R> Comp<B, Q> flatMap(Function<? super A, ? extends Comp<B, Q>> next) {
      return new Asking<>(op, next);
    }
    @Override public Comp<A, R> given(A answer) { return new Pure<>(answer); }
  }
  /** An operation asked, and the program of its answer: the node a free monad is named for. */
  record Asking<X, A, R>(Op<X, ? super R> op, Function<? super X, ? extends Comp<A, R>> next)
      implements Comp<A, R>, Asks<X, A> {
    @Override public Comp<A, R> given(X answer) { return next.apply(answer); }
  }
  /** The two nodes that ask: an operation whose answer is X, and the program that follows, given the answer. */
  private sealed interface Asks<X, A> {
    Op<X, ?> op();
    Comp<A, ?> given(X answer);
  }
  record FlatMap<X, A, R>(Comp<X, ? super R> first, Function<? super X, ? extends Comp<A, R>> next)
      implements Comp<A, R> {}
  /** The part gives A and asks V; the handler is of an effect V has, gives B, and asks what stays, R. */
  record Part<A, B, V, R>(Handler<? super V, A, B, ? super R> handler, Comp<A, ? super V> part)
      implements Comp<B, R> {}
  record Resume<X, S, B, R>(K<X, ?, ?, S, B, R> k, X value, S state) implements Comp<B, R> {}
  record Ensure<A, R>(Comp<A, R> part, Runnable after) implements Comp<A, R> {}
  record Defer<A, R>(Supplier<? extends Comp<A, ? super R>> program) implements Comp<A, R> {}

  public static <A, R> Comp<A, R> pure(A value) { return new Pure<>(value); }

  /**
   * The program is made when it is reached, not where this is written. So a call that asks nothing before it calls
   * again — n == 0 ? pure(true) : defer(() -> odd(n - 1)) — is a step of the machine, and takes no stack.
   */
  public static <A, R> Comp<A, R> defer(Supplier<? extends Comp<A, ? super R>> program) {
    return new Defer<>(Objects.requireNonNull(program, "a program"));
  }

  /** The row has the effect: that is the one bound, R extends F. */
  public static <A, F, R extends F> Comp<A, R> perform(Op<A, F> op) {
    return new Perform<>(Objects.requireNonNull(op, "an operation"));
  }

  /**
   * A handler of the effect F is a fold of the part it is around, with a state S. The part gives A, the handler B.
   * Where the part ends with a value, `ret` gives the handler's, of the last state and that value. Where the part
   * asks an operation of F, its clause does: it is given the state and the rest of the part, k — a function of the
   * operation's answer, X, and the next state, that gives what the handler gives of the rest. What a clause does
   * is its own: it is a program of the row R, what stays outside the part — the rest gone on with (k.apply(x, s)),
   * once or more than once, or a value of its own, the rest left.
   */
  public interface Clause<X, S, B, R> {
    Comp<B, R> on(S state, BiFunction<X, S, Comp<B, R>> k);
  }

  /**
   * A clause, and the operation it was made for. The type of that operation's answer is not in it: so the arms of
   * a handler's switch, each of an operation with an answer of its own, are of one type. Made only by on, keep,
   * answer and leave.
   */
  public sealed interface Case<F, S, B, R> {}

  // What a Case is: one of four, each with what it needs and no more.

  /**
   * The three made for an operation: X is the type of its answer, which Case hides. It is opened in one place,
   * from the side of the operation asked: that is the very one the case was made for, so its answer is X.
   */
  private sealed interface For<X, F, S, B, R> extends Case<F, S, B, R> {
    Op<X, F> op();

    @SuppressWarnings("unchecked")
    default <A> Asks<X, A> asked(Asks<?, A> asks) {
      if (asks.op() == op()) return (Asks<X, A>) asks;
      throw new IllegalStateException("a clause made for " + op() + ", asked was " + asks.op());
    }
  }

  /** answer(op, value): answered where it is asked. */
  private record Answer<X, F, S, B, R>(Op<X, F> op, X answer) implements For<X, F, S, B, R> {}

  /** The two with a clause, which is given the rest of the part, taken off, as k. */
  private sealed interface With<X, F, S, B, R> extends For<X, F, S, B, R> {
    Clause<X, S, B, R> clause();
  }

  /** on(op, clause): when the clause ends, a rest it did not go on with was left, and is closed. */
  private record On<X, F, S, B, R>(Op<X, F> op, Clause<X, S, B, R> clause) implements With<X, F, S, B, R> {}

  /** keep(op, clause): the rest is the clause's to go on with later; nothing is closed when the clause ends. */
  private record Keep<X, F, S, B, R>(Op<X, F> op, Clause<X, S, B, R> clause) implements With<X, F, S, B, R> {}

  /** leave(value): of any operation of the effect, so with no answer's type; the rest is left. */
  private record Leave<F, S, B, R>(B value) implements Case<F, S, B, R> {}

  /** The clause for the operation matched: the type of its answer is the operation's own. */
  public static <X, F, S, B, R> Case<F, S, B, R> on(Op<X, F> op, Clause<X, S, B, R> clause) {
    return new On<>(Objects.requireNonNull(op), clause);
  }

  /**
   * A clause that keeps the rest, to go on with it after the clause has ended: from a callback, in a run of its own
   * (run(k.apply(x, s)), where nothing is left to ask). The rest is then not closed when the clause ends: who
   * keeps it goes on with it, or what it was to close is never closed.
   */
  public static <X, F, S, B, R> Case<F, S, B, R> keep(Op<X, F> op, Clause<X, S, B, R> clause) {
    return new Keep<>(Objects.requireNonNull(op), clause);
  }

  /** The usual clause, on(op, (s, k) -> k.apply(answer, s)): answered where it is asked, nothing taken off. */
  public static <X, F, S, B, R> Case<F, S, B, R> answer(Op<X, F> op, X answer) {
    return new Answer<>(Objects.requireNonNull(op), answer);
  }

  /**
   * A handler of the effect F: of a part that gives A it gives B, and its clauses ask R. The type of its state is
   * not in it: what it is around does not see the state. Made only by folding and handling.
   */
  public sealed interface Handler<F, A, B, R> {}

  /** What a Handler is, with the state's type S, which Handler hides. */
  private record Fold<F, S, A, B, R>(Cases<F, S, B, R> cases, S state, BiFunction<S, A, B> ret)
      implements Handler<F, A, B, R> {}

  /**
   * A handler's clauses, and what its operations are found by: a class, or an object they say. O is what the
   * clauses are given an operation as, which Cases hides.
   */
  private sealed interface Cases<F, S, B, R> {
    /** The operation, which says it is of `of`, is this handler's. */
    boolean has(Op<?, ?> op, Template<?> of);

    /** The clause for an operation that is. */
    Case<F, S, B, R> of(Op<?, ?> op);
  }

  /** folding: the operations are the records of an effect's own interface, and say nothing: found by its class. */
  private record OfClass<O, F, S, B, R>(Class<O> effect, Function<O, Case<F, S, B, R>> cases)
      implements Cases<F, S, B, R> {
    @Override public boolean has(Op<?, ?> op, Template<?> of) { return of == null && effect.isInstance(op); }
    @Override public Case<F, S, B, R> of(Op<?, ?> op) { return cases.apply(effect.cast(op)); }
    @Override public String toString() { return effect.getSimpleName(); }
  }

  /** by: the operations are a template's, found by the object they say — the template made every one that says it. */
  private record OfObject<O, F, S, B, R>(Template<F> said, Function<O, Case<F, S, B, R>> cases)
      implements Cases<F, S, B, R> {
    @Override public boolean has(Op<?, ?> op, Template<?> of) { return of == said; }
    @SuppressWarnings("unchecked")
    @Override public Case<F, S, B, R> of(Op<?, ?> op) { return cases.apply((O) op); }
    @Override public String toString() { return said.toString(); }
  }

  /**
   * folding(Effect.class, state, (s, a) -> b, op -> switch (op) { case X x -> on(x, (s, k) -> ..); .. }):
   * the switch is whole, so no operation of the effect is left out.
   */
  public static <F, S, A, B, R> Handler<F, A, B, R> folding(Class<F> effect, S state, BiFunction<S, A, B> ret,
                                                            Function<F, Case<F, S, B, R>> cases) {
    return new Fold<>(new OfClass<>(Objects.requireNonNull(effect, "the effect's class"),
        Objects.requireNonNull(cases, "the clauses")), state, Objects.requireNonNull(ret, "ret"));
  }

  /**
   * A handler of the operations that say they are of `of` (Op.of): those of a template of effects, said for the
   * effect F. It is found by that object, not by a class; and its clauses are given the operations as what the
   * template made them, O — the template made every operation that says `of`, which is why that is so.
   */
  public static <O, F, S, A, B, R> Handler<F, A, B, R> by(Template<F> of, S state, BiFunction<S, A, B> ret,
                                                         Function<O, Case<F, S, B, R>> cases) {
    return new Fold<>(new OfObject<>(Objects.requireNonNull(of, "of"), Objects.requireNonNull(cases, "the clauses")),
        state, Objects.requireNonNull(ret, "ret"));
  }

  /** A handler with no state that gives what its part gives: handling(Effect.class, op -> switch (op) { .. }). */
  public static <F, B, R> Handler<F, B, B, R> handling(Class<F> effect, Function<F, Case<F, Void, B, R>> cases) {
    return folding(effect, null, (none, b) -> b, cases);
  }

  /** A clause for any operation of the effect, which can only leave the part: with this value. */
  public static <F, S, B, R> Case<F, S, B, R> leave(B value) {
    return new Leave<>(value);
  }

  /**
   * `after` is run when the part ends: with a value; left by a clause that does not go on with its rest; or by an
   * exception. A rest gone on with twice ends twice. It is what closes: it asks nothing.
   */
  public static <A, R> Comp<A, R> ensuring(Comp<A, R> part, Runnable after) { return new Ensure<>(part, after); }

  /**
   * The rest of the part, to its end, as a value: it may be gone on with once, twice or never. X is the answer it
   * takes, S the next state of its handler, B what that handler gives. It is what was asked — whose answer gives
   * a program of A — the steps of the innermost part after that, from A to that part's value, T, and the parts
   * taken with it, each with the state it had: once taken off they are run by no one, and each time the rest goes
   * on it goes on with parts made anew of them. Nothing of it changes but one flag.
   */
  private static final class K<X, A, T, S, B, R> implements BiFunction<X, S, Comp<B, R>> {
    private final Asks<X, A> asks;                   // what was asked
    private final Rest<A, T> steps;                  // the steps of the innermost part, after it
    private final Around<T, ?> top;                  // the parts taken with it, from the innermost
    private final In<?, S, ?, B, ?, ?, ?> own;       //   to the handler's own
    private boolean gone;                            // gone on with, at least once: else it was left

    private K(Asks<X, A> asks, Rest<A, T> steps, Around<T, ?> top, In<?, S, ?, B, ?, ?, ?> own) {
      this.asks = asks; this.steps = steps; this.top = top; this.own = own;
    }

    @Override public Comp<B, R> apply(X answer, S state) { return new Resume<>(this, answer, state); }

    /** What a clause gave, where that is nothing but this rest gone on with: the same object, so of its types. */
    @SuppressWarnings("unchecked")
    private Resume<X, S, B, R> only(Comp<B, R> does) {
      return does instanceof Resume<?, ?, ?, ?> on && on.k() == this ? (Resume<X, S, B, R>) on : null;
    }

    /**
     * k.apply(answer, next), reached: the parts taken off with the rest go back, anew, on top of what is run now.
     * Those inside the handler's own have the states they had when it asked; its own has `next`, and `after` after
     * it; those around it are the ones run now, as they are.
     */
    private <Y, Z> At<Z> back(X answer, S next, Rest<B, Y> after, Around<Y, Z> now) {
      gone = true;
      if (after instanceof Taken<B, Y> mark && mark.k == this) after = mark.after;    // the clause ends with this
      return new Run<>(asks.given(answer), steps, top.anew(this, next, after, now));
    }

    /** Its own part anew. It is the part `from` was: so the part gives what `from` gave, A. */
    @SuppressWarnings("unchecked")
    private <F, P, Q, G, Y, Z> Around<G, Z> its(In<F, S, P, B, Q, ?, ?> own, S state, Rest<B, Y> rest,
                                                Around<Y, Z> now) {
      return (Around<G, Z>) new In<F, S, P, B, Q, Y, Z>(own.handler, state, rest, now);
    }

    /** The rest is left: what it was to close is closed, the innermost first. */
    private void left(Throwable thrown) { close(steps, top, own, thrown); }
  }

  /** Closes what these steps were to close, and then those of the parts from `from` out to `until`, not of it. */
  private static void close(Rest<?, ?> steps, Around<?, ?> from, Around<?, ?> until, Throwable thrown) {
    close(steps, thrown);
    for (Around<?, ?> at = from; at != until && at instanceof In<?, ?, ?, ?, ?, ?, ?> part; at = part.outer) {
      close(part.rest, thrown);
    }
  }

  /** Closes what these steps were to close; under an exception, what closing throws is added to it. */
  private static void close(Rest<?, ?> steps, Throwable thrown) {
    while (!(steps instanceof End)) {
      try {
        switch (steps) {
          case Then<?, ?, ?> step -> steps = step.after;
          case Mapping<?, ?, ?> step -> steps = step.after;
          case Mark<?, ?> mark -> {
            steps = mark.after();
            mark.ended(thrown);
          }
          case End<?> end -> { }
        }
      } catch (Throwable inClosing) {
        if (thrown == null) throw inClosing;
        thrown.addSuppressed(inClosing);
      }
    }
  }

  /**
   * {@code Fx.<R, B>row().handler(outer).handler(inner).handle(part)}: handlers around a part of a program, the
   * outermost first. B is what the whole gives; R the effects that stay, unanswered.
   */
  @SuppressWarnings("unchecked")
  public static <R, B> Handlers<R, R, B, B> row() {
    return new Handlers<R, R, B, B>(part -> (Comp<B, R>) part);      // R is a phantom: nothing of it is in the value
  }

  /** The top: no effect is left. {@code run(Fx.<B>top().handler(h).handle(program))} is a whole application. */
  public static <B> Handlers<Object, Object, B, B> top() { return row(); }

  /**
   * Handlers being put, the outermost first. R is the row that stays, B what the whole gives. W is the row inside
   * the handlers put so far — R and every effect handled here — and A what is to be given there: by the next
   * handler, or by the part.
   */
  public static final class Handlers<R, W, A, B> {
    private final Function<Comp<A, ? super W>, Comp<B, R>> around;   // what is put inside, with the handlers around

    private Handlers(Function<Comp<A, ? super W>, Comp<B, R>> around) { this.around = around; }

    /**
     * The next handler, inside those put so far. Its clauses ask exactly what is around it, W — and so the rest
     * they are given says all it still asks: a handler whose row is less than W is refused here.
     */
    public <V extends W, P> Handlers<R, V, P, B> handler(Handler<? super V, P, A, W> handler) {
      Objects.requireNonNull(handler, "a handler");
      return new Handlers<R, V, P, B>(part -> around.apply(new Part<P, A, V, W>(handler, part)));
    }

    /** javac refuses a part that asks more than W. */
    public Comp<B, R> handle(Comp<A, ? super W> part) {
      return around.apply(part);
    }
  }

  // ---- the machine: the rest of the program is a stack of parts, each a stack of steps ---------------------------
  //
  // Every type here says what goes in and what comes out. Steps take a value of A and end with the value of their
  // part, Z. A part gives A, and the whole program, in the end, Z. So a program of A may be run with steps from A to
  // Y and parts from Y to Z — and nothing else fits: that is the whole of the machine's correctness, and javac's.

  /** The steps of a part: from a value of A to the value of the part, Z. */
  private sealed interface Rest<A, Z> {
    /** Where there are no steps left, the value is the part's: A is Z. Only End says so. */
    default Z end(A value) { throw new IllegalStateException("steps are left: " + this); }
  }

  /** flatMap: a program of the value so far. */
  private record Then<A, X, Z>(Function<? super A, ? extends Comp<X, ?>> next, Rest<X, Z> after)
      implements Rest<A, Z> {}

  /** map: a value of it. */
  private record Mapping<A, X, Z>(Function<? super A, ? extends X> f, Rest<X, Z> after) implements Rest<A, Z> {}

  /** A mark among the steps: when the value passes it, what it was put for has ended. */
  private sealed interface Mark<A, Z> extends Rest<A, Z> {
    Rest<A, Z> after();

    /** It has ended: with a value, or — `thrown` — under an exception. */
    void ended(Throwable thrown);
  }

  /** The mark of ensuring: the part it was for has ended. */
  private record Closing<A, Z>(Runnable close, Rest<A, Z> after) implements Mark<A, Z> {
    @Override public void ended(Throwable thrown) { close.run(); }
  }

  /** The mark under a clause, with the rest that clause was given: a rest it did not go on with was left. */
  private record Taken<A, Z>(K<?, ?, ?, ?, A, ?> k, Rest<A, Z> after) implements Mark<A, Z> {
    @Override public void ended(Throwable thrown) { if (!k.gone) k.left(thrown); }
  }

  /** No steps: the value is the part's. */
  private record End<A>() implements Rest<A, A> {
    @Override public A end(A value) { return value; }
  }

  /** The parts being run: the innermost gives A, the whole program Z. */
  private sealed interface Around<A, Z> {
    /** Where no part is around, the value is the program's: A is Z. Only Top says so. */
    default Z result(A value) { throw new IllegalStateException("parts are left: " + this); }

    /**
     * The parts a rest was taken with, anew, from this one to that rest's own: each with the state it had, its
     * own with `state` and `rest` after it, on top of the parts run now.
     */
    <S, B, Y, W> Around<A, W> anew(K<?, ?, ?, S, B, ?> k, S state, Rest<B, Y> rest, Around<Y, W> now);
  }

  /**
   * A part being run: its handler, and with it the state of that handler, which moves on as the part is run. The
   * part gives A, the handler B of it; the steps after the part take B and end with Y, which the part around gives.
   */
  private static final class In<F, S, A, B, R, Y, Z> implements Around<A, Z> {
    private final Fold<F, S, A, B, R> handler;
    private final Cases<F, S, B, R> cases;   // the handler's, here for the machine that looks for a handler
    private S state;
    private final Rest<B, Y> rest;
    private final Around<Y, Z> outer;

    private In(Fold<F, S, A, B, R> handler, S state, Rest<B, Y> rest, Around<Y, Z> outer) {
      this.handler = handler; this.cases = handler.cases; this.state = state; this.rest = rest; this.outer = outer;
    }

    @Override public <U, C, V, W> Around<A, W> anew(K<?, ?, ?, U, C, ?> k, U state, Rest<C, V> rest, Around<V, W> now) {
      if (this == k.own) return k.its(k.own, state, rest, now);
      return new In<>(handler, this.state, this.rest, outer.anew(k, state, rest, now));
    }
  }

  /** No part: the value is the program's. */
  private record Top<A>() implements Around<A, A> {
    @Override public A result(A value) { return value; }
    @Override public <S, B, Y, W> Around<A, W> anew(K<?, ?, ?, S, B, ?> k, S state, Rest<B, Y> rest, Around<Y, W> now) {
      throw new IllegalStateException("a rest without its handler's part");
    }
  }

  /** Where a program was when it failed: added to the exception, which is thrown on as it is. */
  public static final class Where extends RuntimeException {
    private static final long serialVersionUID = 1;
    private Where(String message) { super(message, null, false, false); }
  }

  /** Runs a program with nothing left to ask. */
  public static <A> A run(Comp<A, Object> program) { return run(program, null); }

  /**
   * Runs it under an observer: `see` is given every operation asked, as data, before it is answered — a log, a
   * metric, an audit, a recording. What it throws stops the program there: an operation it refuses is not answered.
   */
  public static <A> A run(Comp<A, Object> program, Consumer<? super Op<?, ?>> see) {
    return new Machine<A>(see).run(program);
  }

  /**
   * What the machine is at, at one moment — and so what it does next. It is one of three, each with the types of
   * what it holds: a program of some A to run, or a value of A to give, with the steps from A to some Y and the
   * parts from Y to Z; or the program's value, Z. A and Y are hidden in it, as the answer's type is in a Case.
   *
   * <p>The machine is a trampoline: a move takes one of these and gives the next, and a loop does that until the
   * next is Done. A move calls no other move, so a program takes no stack however long it runs; and what a move
   * may give is checked by javac, since the three things fit only one way.
   */
  private sealed interface At<Z> {
    /** The move from here: what the machine is at next. */
    At<Z> next(Machine<Z> machine);
  }

  /** A program to run, the steps that take its value and the parts those steps end in. */
  private record Run<A, Y, Z>(Comp<A, ?> now, Rest<A, Y> rest, Around<Y, Z> around) implements At<Z> {
    @Override public At<Z> next(Machine<Z> machine) { return machine.run(now, rest, around, Machine.STEPS); }
  }

  /** A value, the steps that take it and the parts those steps end in. */
  private record Give<A, Y, Z>(A value, Rest<A, Y> rest, Around<Y, Z> around) implements At<Z> {
    @Override public At<Z> next(Machine<Z> machine) { return machine.give(value, rest, around); }
  }

  /** The program has ended, with this. */
  private record Done<Z>(Z result) implements At<Z> {
    @Override public At<Z> next(Machine<Z> machine) { return this; }
  }

  private static final class Machine<Z> {
    private static final int STEPS = 4;

    private final Consumer<? super Op<?, ?>> see;
    private Op<?, ?> asked;                   // the operation asked last
    private At<Z> failed;                     // where it failed, where that is not where the last move began

    private Machine(Consumer<? super Op<?, ?>> see) { this.see = see; }

    private Z run(Comp<Z, ?> program) {
      At<Z> at = new Run<>(program, new End<>(), new Top<>());
      try {
        while (!(at instanceof Done<Z> done)) at = at.next(this);
        return done.result;
      } catch (Throwable thrown) {                        // what was to be closed is closed, and it is thrown on
        close(thrown, failed != null ? failed : at);
        throw thrown;
      }
    }

    /** One node of the program is run. `more`: how many nodes this move may still go into. */
    private <A, Y> At<Z> run(Comp<A, ?> now, Rest<A, Y> rest, Around<Y, Z> around, int more) {
      return switch (now) {
        case Pure<A, ?> pure -> give(pure.value(), rest, around);
        case Perform<A, ?> asks -> ask(asks, rest, around, more);
        case Asking<?, A, ?> asks -> ask(asks, rest, around, more);
        case FlatMap<?, A, ?> bound -> bind(bound, rest, around, more);
        case Mapped<?, A, ?> mapped -> map(mapped, rest, around, more);
        case Ensure<A, ?> ensured -> new Run<>(ensured.part(), new Closing<>(ensured.after(), rest), around);
        case Part<?, A, ?, ?> part -> part(part, rest, around);
        case Resume<?, ?, A, ?> on -> resume(on, rest, around);
        case Defer<A, ?> deferred -> on(deferred.program().get(), rest, around, more);
      };
    }

    /** Another program, within this move or as the next. */
    private <A, Y> At<Z> on(Comp<A, ?> now, Rest<A, Y> rest, Around<Y, Z> around, int more) {
      return more > 0 ? run(now, rest, around, more - 1) : new Run<>(now, rest, around);
    }

    private <X, A, Y> At<Z> bind(FlatMap<X, A, ?> bound, Rest<A, Y> rest, Around<Y, Z> around, int more) {
      Rest<X, Y> then = new Then<X, A, Y>(bound.next(), rest);
      return on(bound.first(), then, around, more);
    }

    private <X, A, Y> At<Z> map(Mapped<X, A, ?> mapped, Rest<A, Y> rest, Around<Y, Z> around, int more) {
      Rest<X, Y> then = new Mapping<X, A, Y>(mapped.f(), rest);
      return on(mapped.first(), then, around, more);
    }

    /** A handler around a part of the program: the part is run with no steps yet, in a part of its own. */
    private <P, A, Y> At<Z> part(Part<P, A, ?, ?> part, Rest<A, Y> rest, Around<Y, Z> around) {
      return switch (part.handler()) {
        case Fold<?, ?, P, A, ?> handler -> in(handler, part.part(), rest, around);
      };
    }

    private <F, S, P, A, R, Y> At<Z> in(Fold<F, S, P, A, R> handler, Comp<P, ?> part, Rest<A, Y> rest,
                                        Around<Y, Z> around) {
      return new Run<>(part, new End<>(), new In<>(handler, handler.state, rest, around));
    }

    /** An operation is asked: the nearest part whose handler's effect it is of answers. */
    private <X, A, Y> At<Z> ask(Asks<X, A> asks, Rest<A, Y> rest, Around<Y, Z> around, int more) {
      Op<X, ?> op = asks.op();
      asked = op;
      if (see != null) see.accept(op);
      Template<?> of = op.of();                         // what it says it is of; none: its class says
      for (Around<?, Z> at = around; at instanceof In<?, ?, ?, ?, ?, ?, Z> part; at = part.outer) {
        if (part.cases.has(op, of)) return answer(part, asks, rest, around, more);
      }
      throw new IllegalStateException("no handler for " + op);
    }

    /** This part answers: the operation is its handler's. */
    private <F, S, P, B, R, W, A, Y> At<Z> answer(In<F, S, P, B, R, W, Z> own, Asks<?, A> asks, Rest<A, Y> rest,
                                                  Around<Y, Z> around, int more) {
      return switch (own.cases.of(asks.op())) {
        case Answer<?, F, S, B, R> answer -> answered(answer, asks, rest, around, more);
        case With<?, F, S, B, R> with -> taken(own, with, asks, rest, around, more);
        case Leave<F, S, B, R> leave -> left(own, leave.value, asks, rest, around);
      };
    }

    private <X, A, Y> At<Z> answered(Answer<X, ?, ?, ?, ?> answer, Asks<?, A> asks, Rest<A, Y> rest,
                                     Around<Y, Z> around, int more) {
      Comp<A, ?> now = answer.asked(asks).given(answer.answer);
      // a program that is a value — nothing follows the operation — is given, not run: no node is made for it
      return now instanceof Pure<A, ?> pure ? give(pure.value(), rest, around) : on(now, rest, around, more);
    }

    /** A clause is given the rest, to the end of its handler's part. */
    private <X, F, S, P, B, R, W, A, Y> At<Z> taken(In<F, S, P, B, R, W, Z> own, With<X, F, S, B, R> with,
                                                    Asks<?, A> asks, Rest<A, Y> rest, Around<Y, Z> around,
                                                    int more) {
      Asks<X, A> asked = with.asked(asks);
      K<X, A, Y, S, B, R> k = new K<>(asked, rest, around, own);
      Comp<B, R> does = with.clause().on(own.state, k);
      Resume<X, S, B, R> only = with instanceof Keep ? null : k.only(does);
      if (only != null) {
        // The clause only goes on with the rest: nothing was run between, so nothing is taken off or put back. The
        // state moves on where it is, and the answer is given as answer(op, value) gives one. It goes on within
        // this move only where the part that answers is the innermost: found further out, the move is long as it
        // is, and going on within it was measured slower.
        own.state = only.state();
        return on(asked.given(only.value()), rest, around, own == around ? more : 0);
      }
      // The rest is taken off, with its parts: they are the rest's now, and run by no one — so a rest that is kept
      // always is. The clause is a program of what is around that part; under it, unless the rest is kept, a mark:
      // was the rest gone on with.
      return new Run<>(does, with instanceof Keep ? own.rest : new Taken<>(k, own.rest), own.outer);
    }

    /** The part is left with a value: the rest is taken off, and nothing goes on with it. */
    private <X, F, S, P, B, R, W, A, Y> At<Z> left(In<F, S, P, B, R, W, Z> own, B value, Asks<X, A> asks,
                                                   Rest<A, Y> rest, Around<Y, Z> around) {
      K<X, A, Y, S, B, R> k = new K<>(asks, rest, around, own);
      return new Run<>(new Pure<B, R>(value), new Taken<>(k, own.rest), own.outer);
    }

    private <X, S, B, Y> At<Z> resume(Resume<X, S, B, ?> on, Rest<B, Y> rest, Around<Y, Z> around) {
      return on.k().back(on.value(), on.state(), rest, around);
    }

    /** A value is given to the steps: to the next of them, or — none left — to the part. */
    private <A, Y> At<Z> give(A value, Rest<A, Y> rest, Around<Y, Z> around) {
      return give(value, rest, around, STEPS);
    }

    /** `more`: how many steps of a value this move may still take before it gives the next move its turn. */
    private <A, Y> At<Z> give(A value, Rest<A, Y> rest, Around<Y, Z> around, int more) {
      return switch (rest) {
        case Then<A, ?, Y> step -> then(step, value, around);
        case Mapping<A, ?, Y> step -> mapped(step, value, around, more);
        case Mark<A, Y> mark -> {                         // what it was put for has ended
          At<Z> next = new Give<>(value, mark.after(), around);
          failed = next;                                  // should it fail, it is not to be run again
          mark.ended(null);
          failed = null;
          yield next;
        }
        case End<?> none -> ended(rest.end(value), around, more);
      };
    }

    private <A, X, Y> At<Z> then(Then<A, X, Y> step, A value, Around<Y, Z> around) {
      return new Run<>(step.next.apply(value), step.after, around);
    }

    private <A, X, Y> At<Z> mapped(Mapping<A, X, Y> step, A value, Around<Y, Z> around, int more) {
      X mapped = step.f.apply(value);
      return more > 0 ? give(mapped, step.after, around, more - 1) : new Give<>(mapped, step.after, around);
    }

    /** The end of a part: what its handler gives goes on below; and the end of them all. */
    private <Y> At<Z> ended(Y value, Around<Y, Z> around, int more) {
      return switch (around) {
        case In<?, ?, Y, ?, ?, ?, Z> part -> ret(part, value, more);
        case Top<?> none -> new Done<>(around.result(value));
      };
    }

    private <F, S, A, B, R, Y> At<Z> ret(In<F, S, A, B, R, Y, Z> part, A value, int more) {
      B given = part.handler.ret.apply(part.state, value);
      return more > 0 ? give(given, part.rest, part.outer, more - 1) : new Give<>(given, part.rest, part.outer);
    }

    /** Where it failed is added to the exception, and every part still open is closed, the innermost first. */
    private void close(Throwable thrown, At<Z> at) {
      switch (at) {
        case Run<?, ?, Z> run -> close(thrown, run.rest, run.around);
        case Give<?, ?, Z> give -> close(thrown, give.rest, give.around);
        case Done<Z> done -> { }
      }
    }

    private void close(Throwable thrown, Rest<?, ?> rest, Around<?, Z> around) {
      String in = "";
      for (Around<?, ?> at = around; at instanceof In<?, ?, ?, ?, ?, ?, ?> part; at = part.outer) {
        in += " " + part.handler.cases;
      }
      thrown.addSuppressed(new Where("the program had last asked " + asked + "; handlers in force, the nearest first:"
          + (in.isEmpty() ? " none" : in)));
      Fx.close(rest, around, null, thrown);
    }
  }
}
