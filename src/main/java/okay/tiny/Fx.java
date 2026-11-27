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
     * The effect this operation is of, where it is not a record of that effect's own but a generic one, of a
     * template of effects, made for F: then it says so, and its handler is found by that. None: by its class.
     */
    default Class<?> of() { return null; }
  }

  /** A program is data: A is what it gives, R the effects it may ask. */
  public sealed interface Comp<A, R> {
    /** Q: the row of the whole is the row of both parts; a program of fewer effects is one of more as it is. */
    @SuppressWarnings("unchecked")
    default <B, Q extends R> Comp<B, Q> flatMap(Function<? super A, ? extends Comp<B, Q>> next) {
      return new FlatMap<>((Comp<A, Q>) this, next);          // R is a phantom: nothing of it is in the value
    }

    @SuppressWarnings("unchecked")
    default <B, Q extends R> Comp<B, Q> map(Function<? super A, ? extends B> f) {
      return new Mapped<>((Comp<A, Q>) this, f);
    }
  }

  record Pure<A, R>(A value) implements Comp<A, R> {}
  record Mapped<X, A, R>(Comp<X, R> first, Function<? super X, ? extends A> f) implements Comp<A, R> {}
  /** An operation asked, and nothing after it. With flatMap it is the node below: one, not two. */
  record Perform<A, R>(Op<A, ? super R> op) implements Comp<A, R>, Asks<A, A> {
    @Override public <B, Q extends R> Comp<B, Q> flatMap(Function<? super A, ? extends Comp<B, Q>> next) {
      return new Asking<>(op, next);
    }
    @Override public <Y> Rest<A, Y> after(Rest<A, Y> rest) { return rest; }
    @Override public <Y, Z> At<Z> answered(Machine<Z> machine, A answer, Run<A, Y, Z> at, Rest<A, Y> rest,
                                           Around<Y, Z> around) {
      return machine.give(answer, rest, around);
    }
  }
  /** An operation asked, and the program of its answer: the node a free monad is named for. */
  record Asking<X, A, R>(Op<X, ? super R> op, Function<? super X, ? extends Comp<A, R>> next)
      implements Comp<A, R>, Asks<X, A> {
    @Override public <Y> Rest<X, Y> after(Rest<A, Y> rest) { return new Then<>(next, rest); }
    @Override public <Y, Z> At<Z> answered(Machine<Z> machine, X answer, Run<A, Y, Z> at, Rest<A, Y> rest,
                                           Around<Y, Z> around) {
      return machine.on(at, next.apply(answer), rest, around);
    }
  }
  /**
   * The two nodes that ask: an operation whose answer is X, in a program that gives A. What they differ in is what
   * follows the answer: the steps that take it, and what the machine does with it where it is given in place.
   */
  private sealed interface Asks<X, A> {
    Op<X, ?> op();
    <Y> Rest<X, Y> after(Rest<A, Y> rest);
    <Y, Z> At<Z> answered(Machine<Z> machine, X answer, Run<A, Y, Z> at, Rest<A, Y> rest, Around<Y, Z> around);
  }
  record FlatMap<X, A, R>(Comp<X, R> first, Function<? super X, ? extends Comp<A, R>> next) implements Comp<A, R> {}
  /** The part gives A and asks V; the handler is of an effect V has, gives B, and asks what stays, R. */
  record Part<A, B, V, R>(Handler<? super V, A, B, ? super R> handler, Comp<A, ? super V> part)
      implements Comp<B, R> {}
  record Resume<X, S, B, R>(K<X, ?, S, B, R> k, X value, S state) implements Comp<B, R> {}
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

  // What a Case is: one of four, each with what it needs and no more. X is the answer's type, which Case hides.

  /** answer(op, value): answered where it is asked. */
  private record Answer<X, F, S, B, R>(Op<X, F> op, X answer) implements Case<F, S, B, R> {
    /** Its answer, for the operation asked: that is the one it was made for, so the answer is of its type. */
    @SuppressWarnings("unchecked")
    private <Y> Y of(Op<Y, ?> asked) { return (Y) made(op, asked, answer); }
  }

  /** The others take the rest of the part off, as k. */
  private sealed interface Takes<F, S, B, R> extends Case<F, S, B, R> {}

  /** on(op, clause): when the clause ends, a rest it did not go on with was left, and is closed. */
  private record On<X, F, S, B, R>(Op<X, F> op, Clause<X, S, B, R> clause) implements Takes<F, S, B, R> {
    @SuppressWarnings("unchecked")
    private <Y> Clause<Y, S, B, R> of(Op<Y, ?> asked) { return (Clause<Y, S, B, R>) made(op, asked, clause); }
  }

  /** keep(op, clause): the rest is the clause's to go on with later; nothing is closed when the clause ends. */
  private record Keep<X, F, S, B, R>(Op<X, F> op, Clause<X, S, B, R> clause) implements Takes<F, S, B, R> {
    @SuppressWarnings("unchecked")
    private <Y> Clause<Y, S, B, R> of(Op<Y, ?> asked) { return (Clause<Y, S, B, R>) made(op, asked, clause); }
  }

  /** leave(value): of any operation of the effect, so with no answer's type; the rest is left. */
  private record Leave<F, S, B, R>(B value) implements Takes<F, S, B, R> {}

  /** What a case holds, for the operation asked: the case was made for that very one. */
  private static <T> T made(Object made, Object asked, T held) {
    if (made != asked) throw new IllegalStateException("a clause made for " + made + ", asked was " + asked);
    return held;
  }

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
  private record Fold<F, S, A, B, R>(Class<F> effect, S state, BiFunction<S, A, B> ret,
                                     Function<F, Case<F, S, B, R>> cases) implements Handler<F, A, B, R> {
    /** The operation as one of this handler's effect: it is of it, the machine has just found the handler by that. */
    @SuppressWarnings("unchecked")
    private F of(Op<?, ?> op) { return (F) op; }
  }

  /**
   * folding(Effect.class, state, (s, a) -> b, op -> switch (op) { case X x -> on(x, (s, k) -> ..); .. }):
   * the switch is whole, so no operation of the effect is left out.
   */
  public static <F, S, A, B, R> Handler<F, A, B, R> folding(Class<F> effect, S state, BiFunction<S, A, B> ret,
                                                            Function<F, Case<F, S, B, R>> cases) {
    return new Fold<>(Objects.requireNonNull(effect, "the effect's class"), state,
        Objects.requireNonNull(ret, "ret"), Objects.requireNonNull(cases, "the clauses"));
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
   * takes, S the next state of its handler, B what that handler gives. It is the steps of the innermost part —
   * from X to that part's value, T — and the parts taken with it, each with the state it had: once taken off they
   * are run by no one, and each time the rest goes on it goes on with parts made anew of them.
   */
  private static final class K<X, T, S, B, R> implements BiFunction<X, S, Comp<B, R>> {
    private final Rest<X, T> rest;                   // the steps of the innermost part
    private Around<T, ?> top;                        // the parts taken with it, from the innermost
    private In<?, S, ?, B, ?, ?, ?> own;             //   to the handler's own
    private In<?, S, ?, B, ?, ?, ?> made;            // its own, among parts made anew
    private boolean gone;                            // gone on with, at least once: else it was left

    private K(Rest<X, T> rest, Around<T, ?> top, In<?, S, ?, B, ?, ?, ?> own) {
      this.rest = rest; this.top = top; this.own = own;
    }

    @Override public Comp<B, R> apply(X answer, S state) { return new Resume<>(this, answer, state); }

    /** What a clause gave, where that is nothing but this rest gone on with: the same object, so of its types. */
    @SuppressWarnings("unchecked")
    private Resume<X, S, B, R> only(Comp<B, R> does) {
      return does instanceof Resume<?, ?, ?, ?> on && on.k() == this ? (Resume<X, S, B, R>) on : null;
    }

    /**
     * The parts the rest was taken with, anew: each with the state it had, its own with `state` and `rest` after
     * it, on top of the parts run now.
     */
    private <Y, Z> Around<T, Z> onto(S state, Rest<B, Y> rest, Around<Y, Z> now) {
      return top.anew(this, state, rest, now);
    }

    /** Its own part anew. It is the part `from` was: so the part gives what `from` gave, A. */
    @SuppressWarnings("unchecked")
    private <F, P, Q, A, Y, Z> Around<A, Z> its(In<F, S, P, B, Q, ?, ?> own, S state, Rest<B, Y> rest,
                                                Around<Y, Z> now) {
      In<F, S, P, B, Q, Y, Z> anew = new In<>(own.handler, state, rest, now);
      made = anew;
      return (Around<A, Z>) anew;
    }

    /** For a rest that is kept while its parts go on being run: parts of its own, with the states as they are. */
    private void keep() { keep(own); }

    private <Y, Z> void keep(In<?, S, ?, B, ?, Y, Z> own) {
      top = top.anew(this, own.state, own.rest, own.outer);
      this.own = made;
    }

    /** The rest is left: what it was to close is closed, the innermost first. */
    private void left(Throwable thrown) {
      close(rest, thrown);
      Around<?, ?> part = top;
      while (part != own && part instanceof In<?, ?, ?, ?, ?, ?, ?> inside) {
        close(inside.rest, thrown);
        part = inside.outer;
      }
    }
  }

  /** Closes what these steps were to close; under an exception, what closing throws is added to it. */
  private static void close(Rest<?, ?> steps, Throwable thrown) {
    while (!(steps instanceof End)) {
      try {
        switch (steps) {
          case Then<?, ?, ?> step -> steps = step.after;
          case Mapping<?, ?, ?> step -> steps = step.after;
          case Closing<?, ?> mark -> {
            steps = mark.after;
            mark.close.run();
          }
          case Taken<?, ?> mark -> {
            steps = mark.after;
            if (!mark.k.gone) mark.k.left(thrown);
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
  public static <R, B> Handlers<R, R, B, B> row() { return new Handlers<>(part -> part); }

  /** The top: no effect is left. {@code run(Fx.<B>top().handler(h).handle(program))} is a whole application. */
  public static <B> Handlers<Object, Object, B, B> top() { return row(); }

  /**
   * Handlers being put, the outermost first. R is the row that stays, B what the whole gives. W is the row inside
   * the handlers put so far — R and every effect handled here — and A what is to be given there: by the next
   * handler, or by the part.
   */
  public static final class Handlers<R, W, A, B> {
    private final Function<Comp<?, ?>, Comp<?, ?>> around;     // what is put inside, with the handlers around it

    private Handlers(Function<Comp<?, ?>, Comp<?, ?>> around) { this.around = around; }

    /**
     * The next handler, inside those put so far. Its clauses ask exactly what is around it, W — and so the rest
     * they are given says all it still asks: a handler whose row is less than W is refused here.
     */
    @SuppressWarnings("unchecked")
    public <V extends W, P> Handlers<R, V, P, B> handler(Handler<? super V, P, A, W> handler) {
      Objects.requireNonNull(handler, "a handler");
      return new Handlers<>(part -> around.apply(new Part<P, A, V, W>(handler, (Comp<P, ? super V>) part)));
    }

    /** javac refuses a part that asks more than W. */
    @SuppressWarnings("unchecked")
    public Comp<B, R> handle(Comp<A, ? super W> part) {
      return (Comp<B, R>) around.apply(part);
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

  /** A mark of ensuring: when the value passes it, the part it was for has ended. */
  private record Closing<A, Z>(Runnable close, Rest<A, Z> after) implements Rest<A, Z> {}

  /** A mark under a clause: the rest that clause was given, to see when it ends whether it went on with it. */
  private record Taken<A, Z>(K<?, ?, ?, A, ?> k, Rest<A, Z> after) implements Rest<A, Z> {}

  /** No steps: the value is the part's. */
  private record End<A>() implements Rest<A, A> {
    private static final End<Object> NONE = new End<>();
    @Override public A end(A value) { return value; }
  }

  @SuppressWarnings("unchecked")
  private static <A> Rest<A, A> end() { return (Rest<A, A>) (Rest<?, ?>) End.NONE; }      // one End, of any type

  /** The parts being run: the innermost gives A, the whole program Z. */
  private sealed interface Around<A, Z> {
    /** Where no part is around, the value is the program's: A is Z. Only Top says so. */
    default Z result(A value) { throw new IllegalStateException("parts are left: " + this); }

    /**
     * The parts a rest was taken with, anew, from this one to that rest's own: each with the state it had, its
     * own with `state` and `rest` after it, on top of the parts run now.
     */
    <S, B, Y, W> Around<A, W> anew(K<?, ?, S, B, ?> k, S state, Rest<B, Y> rest, Around<Y, W> now);
  }

  /**
   * A part being run: its handler, and with it the state of that handler, which moves on as the part is run. The
   * part gives A, the handler B of it; the steps after the part take B and end with Y, which the part around gives.
   */
  private static final class In<F, S, A, B, R, Y, Z> implements Around<A, Z> {
    private final Fold<F, S, A, B, R> handler;
    private S state;
    private final Rest<B, Y> rest;
    private final Around<Y, Z> outer;

    private In(Fold<F, S, A, B, R> handler, S state, Rest<B, Y> rest, Around<Y, Z> outer) {
      this.handler = handler; this.state = state; this.rest = rest; this.outer = outer;
    }

    @Override public <U, C, V, W> Around<A, W> anew(K<?, ?, U, C, ?> k, U state, Rest<C, V> rest, Around<V, W> now) {
      if (this == k.own) return k.its(k.own, state, rest, now);
      return new In<>(handler, this.state, this.rest, outer.anew(k, state, rest, now));
    }
  }

  /** No part: the value is the program's. */
  private record Top<A>() implements Around<A, A> {
    private static final Top<Object> NONE = new Top<>();
    @Override public A result(A value) { return value; }
    @Override public <S, B, Y, W> Around<A, W> anew(K<?, ?, S, B, ?> k, S state, Rest<B, Y> rest, Around<Y, W> now) {
      throw new IllegalStateException("a rest without its handler's part");
    }
  }

  @SuppressWarnings("unchecked")
  private static <A> Around<A, A> none() { return (Around<A, A>) (Around<?, ?>) Top.NONE; }   // one Top, of any type

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
  private static final class Run<A, Y, Z> implements At<Z> {
    private Comp<A, ?> now;                  // moves on where it is, while what it gives, A, is the same
    private final Rest<A, Y> rest;
    private final Around<Y, Z> around;

    private Run(Comp<A, ?> now, Rest<A, Y> rest, Around<Y, Z> around) {
      this.now = now; this.rest = rest; this.around = around;
    }

    @Override public At<Z> next(Machine<Z> machine) { return machine.run(this, now, rest, around, Machine.STEPS); }

    /** The same steps and parts, another program of the same A: this, and nothing is made. */
    private At<Z> on(Comp<A, ?> now) {
      this.now = now;
      return this;
    }
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
      At<Z> at = new Run<>(program, Fx.<Z>end(), Fx.<Z>none());
      try {
        while (!(at instanceof Done<Z> done)) at = at.next(this);
        return done.result;
      } catch (Throwable thrown) {                        // what was to be closed is closed, and it is thrown on
        close(thrown, failed != null ? failed : at);
        throw thrown;
      }
    }

    /** One node of the program is run. */
    /**
     * `at`: the Run this move began with, where it is still of these types — to move on in place; else none.
     * `more`: how many nodes this move may still go into before it gives the next move its turn.
     */
    private <A, Y> At<Z> run(Run<A, Y, Z> at, Comp<A, ?> now, Rest<A, Y> rest, Around<Y, Z> around, int more) {
      return switch (now) {
        case Pure<A, ?> pure -> give(pure.value(), rest, around);
        case Perform<A, ?> asks -> ask(asks, at, rest, around);
        case Asking<?, A, ?> asks -> ask(asks, at, rest, around);
        case FlatMap<?, A, ?> bound -> bind(bound, rest, around, more);
        case Mapped<?, A, ?> mapped -> map(mapped, rest, around, more);
        case Ensure<A, ?> ensured -> new Run<>(ensured.part(), new Closing<>(ensured.after(), rest), around);
        case Part<?, A, ?, ?> part -> part(part, rest, around);
        case Resume<?, ?, A, ?> on -> resume(on, rest, around);
        case Defer<A, ?> deferred -> on(at, deferred.program().get(), rest, around);
      };
    }

    /** The same steps and parts, another program of the same A: the Run it began with, where there is one. */
    private <A, Y> At<Z> on(Run<A, Y, Z> at, Comp<A, ?> now, Rest<A, Y> rest, Around<Y, Z> around) {
      return at != null ? at.on(now) : new Run<>(now, rest, around);
    }

    private <X, A, Y> At<Z> bind(FlatMap<X, A, ?> bound, Rest<A, Y> rest, Around<Y, Z> around, int more) {
      Rest<X, Y> then = new Then<X, A, Y>(bound.next(), rest);
      return more > 0 ? run(null, bound.first(), then, around, more - 1) : new Run<>(bound.first(), then, around);
    }

    private <X, A, Y> At<Z> map(Mapped<X, A, ?> mapped, Rest<A, Y> rest, Around<Y, Z> around, int more) {
      Rest<X, Y> then = new Mapping<X, A, Y>(mapped.f(), rest);
      return more > 0 ? run(null, mapped.first(), then, around, more - 1) : new Run<>(mapped.first(), then, around);
    }

    /** A handler around a part of the program: the part is run with no steps yet, in a part of its own. */
    private <P, A, Y> At<Z> part(Part<P, A, ?, ?> part, Rest<A, Y> rest, Around<Y, Z> around) {
      return switch (part.handler()) {
        case Fold<?, ?, P, A, ?> handler -> in(handler, part.part(), rest, around);
      };
    }

    private <F, S, P, A, R, Y> At<Z> in(Fold<F, S, P, A, R> handler, Comp<P, ?> part, Rest<A, Y> rest,
                                        Around<Y, Z> around) {
      return new Run<>(part, Fx.<P>end(), new In<>(handler, handler.state, rest, around));
    }

    /** An operation is asked: the nearest part whose handler's effect it is of answers. */
    private <X, A, Y> At<Z> ask(Asks<X, A> asks, Run<A, Y, Z> at, Rest<A, Y> rest, Around<Y, Z> around) {
      Op<X, ?> op = asks.op();
      asked = op;
      if (see != null) see.accept(op);
      Class<?> of = op.of();                            // the effect it says it is of; none: its class says
      Around<?, Z> own = around;
      while (own instanceof In<?, ?, ?, ?, ?, ?, Z> part) {
        Class<?> effect = part.handler.effect;
        if (of == null ? effect.isInstance(op) : effect == of) return answer(part, asks, op, at, rest, around);
        own = part.outer;
      }
      throw new IllegalStateException("no handler for " + op);
    }

    /** This part answers: the operation is of its handler's effect. */
    private <F, S, P, B, R, W, X, A, Y> At<Z> answer(In<F, S, P, B, R, W, Z> own, Asks<X, A> asks, Op<X, ?> op,
                                                     Run<A, Y, Z> at, Rest<A, Y> rest, Around<Y, Z> around) {
      switch (own.handler.cases.apply(own.handler.of(op))) {
        case Answer<?, F, S, B, R> answered -> {
          return asks.answered(this, answered.of(op), at, rest, around);
        }
        case Takes<F, S, B, R> takes -> {                 // the rest, to the end of the handler's part
          Rest<X, Y> after = asks.after(rest);
          K<X, Y, S, B, R> k = new K<>(after, around, own);
          Comp<B, R> does = switch (takes) {
            case On<?, F, S, B, R> on -> on.of(op).on(own.state, k);
            case Keep<?, F, S, B, R> keep -> keep.of(op).on(own.state, k);
            case Leave<F, S, B, R> leave -> new Pure<>(leave.value);
          };
          Resume<X, S, B, R> only = k.only(does);
          if (only != null) {
            // The clause only goes on with the rest: nothing was run between, so nothing is taken off or put back,
            // and the state moves on where it is. (A rest that may be kept takes parts of its own.)
            if (takes instanceof Keep) k.keep();
            own.state = only.state();
            return give(only.value(), after, around);
          }
          // The rest is taken off, with its parts: they are the rest's now, and run by no one. The clause is a
          // program of what is around that part; under it, a mark: was the rest gone on with.
          return new Run<>(does, takes instanceof Keep ? own.rest : new Taken<>(k, own.rest), own.outer);
        }
      }
    }

    /**
     * k.apply(answer, next): the parts taken off with the rest go back, anew, on top of what is run now. Those
     * inside the handler's own have the states they had when it asked; its own has `next`; those around it are the
     * ones run now, as they are.
     */
    private <X, S, B, R, Y> At<Z> resume(Resume<X, S, B, R> on, Rest<B, Y> rest, Around<Y, Z> around) {
      return back(on.k(), on, rest, around);
    }

    private <X, T, S, B, R, Y> At<Z> back(K<X, T, S, B, R> k, Resume<X, S, B, R> on, Rest<B, Y> rest,
                                          Around<Y, Z> around) {
      k.gone = true;
      if (rest instanceof Taken<B, Y> mark && mark.k == k) rest = mark.after;     // the clause ends with this: its mark
      return new Give<>(on.value(), k.rest, k.onto(on.state(), rest, around));
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
        case Closing<A, Y> mark -> {                      // the part it was for has ended
          At<Z> next = new Give<>(value, mark.after, around);
          failed = next;                                  // should it fail, it is not to be run again
          mark.close.run();
          failed = null;
          yield next;
        }
        case Taken<A, Y> mark -> {                        // its clause has ended: was the rest gone on with?
          At<Z> next = new Give<>(value, mark.after, around);
          if (!mark.k.gone) {
            failed = next;
            mark.k.left(null);
            failed = null;
          }
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
        in += " " + part.handler.effect.getSimpleName();
      }
      thrown.addSuppressed(new Where("the program had last asked " + asked + "; handlers in force, the nearest first:"
          + (in.isEmpty() ? " none" : in)));
      Fx.close(rest, thrown);
      for (Around<?, ?> at = around; at instanceof In<?, ?, ?, ?, ?, ?, ?> part; at = part.outer) {
        Fx.close(part.rest, thrown);
      }
    }
  }
}
