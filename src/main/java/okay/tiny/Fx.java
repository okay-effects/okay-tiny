package okay.tiny;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/** Algebraic effects on a free monad, the least of it: one file, Java 21, no threads, no reflection, no dependency. */
public final class Fx {
  private Fx() {}

  /** An operation is data: a record of a sealed interface, the effect F, that says the type of its answer, A. */
  public interface Op<A, F> {}

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
  record Perform<A, R>(Op<?, ?> op) implements Comp<A, R> {}
  record FlatMap<X, A, R>(Comp<X, R> first, Function<? super X, ? extends Comp<A, R>> next) implements Comp<A, R> {}
  record Part<A, R>(Handler<?, ?, ?, ?> handler, Comp<?, ?> part) implements Comp<A, R> {}
  record Resume<A, R>(K<?, ?, ?, ?> k, Object value, Object state) implements Comp<A, R> {}
  record Ensure<A, R>(Comp<A, R> part, Runnable after) implements Comp<A, R> {}

  public static <A, R> Comp<A, R> pure(A value) { return new Pure<>(value); }

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

  /** A clause, and the operation it was made for. */
  public static final class Case<F, S, B, R> {
    private final Object op;                             // none: any operation of the effect (leave)
    private final Clause<Object, S, B, R> clause;        // none: the operation is only answered, with
    private final Object answer;
    private final boolean kept;                          // the rest is not closed when the clause ends

    @SuppressWarnings("unchecked")
    private Case(Object op, Clause<?, S, B, R> clause, Object answer, boolean kept) {
      this.op = op;
      this.kept = kept;
      this.clause = (Clause<Object, S, B, R>) clause;
      this.answer = answer;
    }
  }

  /** The clause for the operation matched: the type of its answer is the operation's own. */
  public static <X, F, S, B, R> Case<F, S, B, R> on(Op<X, F> op, Clause<X, S, B, R> clause) {
    return new Case<>(Objects.requireNonNull(op), clause, null, false);
  }

  /**
   * A clause that keeps the rest, to go on with it after the clause has ended: from a callback, in a run of its own
   * (run(k.apply(x, s)), where nothing is left to ask). The rest is then not closed when the clause ends: who
   * keeps it goes on with it, or what it was to close is never closed.
   */
  public static <X, F, S, B, R> Case<F, S, B, R> keep(Op<X, F> op, Clause<X, S, B, R> clause) {
    return new Case<>(Objects.requireNonNull(op), clause, null, true);
  }

  /** The usual clause, on(op, (s, k) -> k.apply(answer, s)): answered where it is asked, nothing taken off. */
  public static <X, F, S, B, R> Case<F, S, B, R> answer(Op<X, F> op, X answer) {
    return new Case<>(Objects.requireNonNull(op), null, answer, false);
  }

  public static final class Handler<F, A, B, R> {
    private final Class<F> effect;
    private final Object state;
    private final BiFunction<Object, Object, Object> ret;
    private final Function<Object, Case<F, Object, B, R>> cases;

    @SuppressWarnings("unchecked")
    private <S> Handler(Class<F> effect, S state, BiFunction<S, A, B> ret, Function<F, Case<F, S, B, R>> cases) {
      this.effect = effect;
      this.state = state;
      this.ret = (BiFunction<Object, Object, Object>) (BiFunction<?, ?, ?>) ret;
      this.cases = (Function<Object, Case<F, Object, B, R>>) (Function<?, ?>) cases;
    }
  }

  /**
   * folding(Effect.class, state, (s, a) -> b, op -> switch (op) { case X x -> on(x, (s, k) -> ..); .. }):
   * the switch is whole, so no operation of the effect is left out.
   */
  public static <F, S, A, B, R> Handler<F, A, B, R> folding(Class<F> effect, S state, BiFunction<S, A, B> ret,
                                                            Function<F, Case<F, S, B, R>> cases) {
    return new Handler<>(Objects.requireNonNull(effect, "the effect's class"), state,
        Objects.requireNonNull(ret, "ret"), Objects.requireNonNull(cases, "the clauses"));
  }

  /** A handler with no state that gives what its part gives: handling(Effect.class, op -> switch (op) { .. }). */
  public static <F, B, R> Handler<F, B, B, R> handling(Class<F> effect, Function<F, Case<F, Void, B, R>> cases) {
    return folding(effect, null, (none, b) -> b, cases);
  }

  /** A clause for any operation of the effect, which can only leave the part: with this value. */
  public static <F, S, B, R> Case<F, S, B, R> leave(B value) {
    return new Case<>(null, (state, k) -> pure(value), null, false);
  }

  /**
   * `after` is run when the part ends: with a value; left by a clause that does not go on with its rest; or by an
   * exception. A rest gone on with twice ends twice. It is what closes: it asks nothing.
   */
  public static <A, R> Comp<A, R> ensuring(Comp<A, R> part, Runnable after) { return new Ensure<>(part, after); }

  /** The rest of the part, to its end, as a value: immutable, so it may be gone on with once, twice or never. */
  private static final class K<X, S, B, R> implements BiFunction<X, S, Comp<B, R>> {
    private final Rest rest;                 // the steps of the innermost part
    private final Around top, own;           // the parts taken with it: from the innermost to the handler's own
    private final Object[] states;
    private final Object state;
    private boolean gone;                    // gone on with, at least once: else, when its clause ends, it was left

    private K(Rest rest, Around top, Around own, Object[] states, Object state) {
      this.rest = rest; this.top = top; this.own = own; this.states = states; this.state = state;
    }

    @Override public Comp<B, R> apply(X answer, S state) { return new Resume<>(this, answer, state); }

    /** The parts the rest was taken with, from its innermost to the handler's own, on top of those run now. */
    private Around onto(Around from, int deeper, Rest rest, Around now) {
      return from == own ? new Around(from.handler, from.depth + deeper, rest, now)
          : new Around(from.handler, from.depth + deeper, from.rest, onto(from.outer, deeper, rest, now));
    }

    /** The rest is left: what it was to close is closed, the innermost first. */
    private void left(Throwable thrown) {
      close(rest, thrown);
      for (Around part = top; part != own; part = part.outer) close(part.rest, thrown);
    }
  }

  /** Closes what these steps were to close; under an exception, what closing throws is added to it. */
  private static void close(Rest steps, Throwable thrown) {
    for (; steps != null; steps = steps.after) {
      try {
        if (steps.mark instanceof Runnable after) after.run();
        else if (steps.mark instanceof K<?, ?, ?, ?> k && !k.gone) k.left(thrown);
      } catch (Throwable inClosing) {
        if (thrown == null) throw inClosing;
        thrown.addSuppressed(inClosing);
      }
    }
  }

  /** {@code Fx.<R, A>row().handler(h).handle(part)}: the part gives A and may ask R and the handlers' effects. */
  public static <R, A> Handlers<R, R, A, A> row() { return new Handlers<>(part -> part); }

  /** The top: no effect is left. {@code run(Fx.<A>top().handler(h).handle(program))} is a whole application. */
  public static <A> Handlers<Object, Object, A, A> top() { return row(); }

  /**
   * R is the row that stays, W the set the part may ask: R and every effect handled here, inferred by javac. A is
   * what the part gives, B what the handlers give of it: each is around those before it.
   */
  public static final class Handlers<R, W, A, B> {
    private final Function<Comp<?, ?>, Comp<?, ?>> around;     // a part, with the handlers put so far around it

    private Handlers(Function<Comp<?, ?>, Comp<?, ?>> around) { this.around = around; }

    /** A handler may itself ask only what stays: R. */
    public <V extends W, C> Handlers<R, V, A, C> handler(Handler<? super V, B, C, ? super R> handler) {
      Objects.requireNonNull(handler, "a handler");
      return new Handlers<>(part -> new Part<>(handler, around.apply(part)));
    }

    /** javac refuses a part that asks more than W. */
    @SuppressWarnings("unchecked")
    public Comp<B, R> handle(Comp<A, ? super W> part) {
      return (Comp<B, R>) around.apply(part);
    }
  }

  // ---- the machine: one loop; the rest of the program is a stack of parts, each a stack of steps ------------------

  /**
   * A step of the rest of a part: a program of the value so far; or, `mapped`, only a value of it. Or a mark, which
   * the value passes: what to run when the part ends (ensuring), or the rest a clause was given, to see when that
   * clause ends whether it went on with it.
   */
  private record Rest(Function<Object, Object> next, boolean mapped, Object mark, Rest after) {}

  /** A part being run: its handler, how many parts are around it, the steps after it, and the part around it. */
  private record Around(Handler<?, ?, ?, ?> handler, int depth, Rest rest, Around outer) {}

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
  @SuppressWarnings("unchecked")
  public static <A> A run(Comp<A, Object> program, Consumer<? super Op<?, ?>> see) {
    Comp<?, ?> now = program;
    Rest rest = null;                     // the steps of the part being run
    Around around = null;                 // that part, and those around it
    Object state = null;                  // the state of that part's handler; and of those around it,
    Object[] states = {};                 //   each at its part's depth: an array never changed once it is shared
    Object asked = null;                  // the operation asked last
    try {
      while (true) {
        Object value;
        switch (now) {
          case Pure<?, ?> v -> value = v.value();
          case FlatMap<?, ?, ?> m -> {
            rest = new Rest((Function<Object, Object>) (Function<?, ?>) m.next(), false, null, rest);
            now = m.first();
            continue;
          }
          case Mapped<?, ?, ?> m -> {
            rest = new Rest((Function<Object, Object>) (Function<?, ?>) m.f(), true, null, rest);
            now = m.first();
            continue;
          }
          case Ensure<?, ?> e -> {
            rest = new Rest(null, true, e.after(), rest);
            now = e.part();
            continue;
          }
          case Part<?, ?> part -> {                     // a handler around a part of the program
            if (around != null) states = with(states, around.depth, state);
            around = new Around(part.handler(), around == null ? 0 : around.depth + 1, rest, around);
            state = part.handler().state;
            rest = null;
            now = part.part();
            continue;
          }
          case Perform<?, ?> p -> {
            Object op = asked = p.op();
            if (see != null) see.accept(p.op());
            Around own = around;
            while (own != null && !own.handler.effect.isInstance(op)) own = own.outer;
            if (own == null) throw new IllegalStateException("no handler for " + op);
            var clause = (Case<?, Object, Object, Object>) own.handler.cases.apply(op);
            if (clause.op != null && clause.op != op) {
              throw new IllegalStateException("a clause made for " + clause.op + ", asked was " + op);
            }
            if (clause.clause != null) {                // the rest, to the end of the handler's part, is taken off
              K<Object, Object, Object, Object> k = new K<>(rest, around, own, states, state);
              Object its = own == around ? state : states[own.depth];
              // the clause is a program of what is around that part; under it, a mark: was the rest gone on with
              rest = clause.kept ? own.rest : new Rest(null, true, k, own.rest);
              around = own.outer;
              if (around != null) state = states[around.depth];
              now = clause.clause.on(its, k);
              continue;
            }
            value = clause.answer;
          }
          // k.apply(answer, next): the parts taken off with the rest go back on top of what is run now.
          //
          //   when it asked                 the clause runs                 the rest goes on
          //   top  [C]  state in `state`
          //        [B]  states[2]      k = the rest and [C] [B] [own]       [C]  as it was when it asked
          //   own  [H]  states[1]                                           [B]  as it was when it asked
          //        [A]  states[0]           [A] `state`: may change         [H]  next
          //                                                                 [A]  as it is now
          //
          // The state of the innermost part is in `state`, those of the others in `states`, by depth. So: nothing
          // inside its own part — only `state` is set; else one copy of the array, with `next` at its own depth.
          case Resume<?, ?> r -> {                      // the rest is put back, on top of what the clause is at
            K<?, ?, ?, ?> k = r.k();
            k.gone = true;
            if (rest != null && rest.mark == k) rest = rest.after;      // the clause ends with this: its mark too
            int depth = around == null ? 0 : around.depth + 1;          // of the handler's own part, as put now
            int inside = k.top.depth - k.own.depth;                     // the parts the rest has inside that one
            if (around != null) states = with(states, around.depth, state);
            if (inside > 0 || depth != k.own.depth) {   // the states inside: as they were when it asked
              Object[] next;
              if (states == k.states && depth == k.own.depth) next = states.clone(); // nothing around has changed
              else {
                next = Arrays.copyOf(states, Math.max(states.length, depth + 1 + inside));
                if (inside > 1) System.arraycopy(k.states, k.own.depth + 1, next, depth + 1, inside - 1);
              }
              next[depth] = r.state();
              states = next;
            }
            state = inside > 0 ? k.state : r.state();   // its own: the next one
            boolean same = rest == k.own.rest && around == k.own.outer; // a clause that only goes on: no part anew
            around = same ? k.top : k.onto(k.top, depth - k.own.depth, rest, around);
            rest = k.rest;
            value = r.value();
          }
        }
        while (true) {
          while (rest == null) {                        // the end of a part: what its handler gives goes on below
            if (around == null) return (A) value;
            value = around.handler.ret.apply(state, value);
            rest = around.rest;
            around = around.outer;
            if (around != null) state = states[around.depth];
          }
          Rest step = rest;
          rest = step.after;
          if (step.mark == null) {
            value = step.next.apply(value);
            if (!step.mapped) break;
          } else if (step.mark instanceof Runnable after) after.run();           // the part it was for has ended
          else if (!((K<?, ?, ?, ?>) step.mark).gone) ((K<?, ?, ?, ?>) step.mark).left(null);    // its clause has
        }
        now = (Comp<?, ?>) value;
      }
    } catch (Throwable thrown) {                        // what was to be closed is closed, and it is thrown on
      String in = "";
      for (Around part = around; part != null; part = part.outer) in += " " + part.handler.effect.getSimpleName();
      thrown.addSuppressed(new Where("the program had last asked " + asked + "; handlers in force, the nearest first:"
          + (in.isEmpty() ? " none" : in)));
      close(rest, thrown);
      for (Around part = around; part != null; part = part.outer) close(part.rest, thrown);
      throw thrown;
    }
  }

  /** The states with this one at this depth: the same array where it is there already. */
  private static Object[] with(Object[] states, int depth, Object state) {
    if (depth < states.length && states[depth] == state) return states;
    Object[] next = Arrays.copyOf(states, Math.max(states.length, depth + 1));
    next[depth] = state;
    return next;
  }
}
