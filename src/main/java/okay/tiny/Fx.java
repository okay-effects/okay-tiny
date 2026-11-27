package okay.tiny;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

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
  /** An operation asked, and nothing after it. With flatMap it is the node below: one, not two. */
  record Perform<A, R>(Op<A, ? super R> op) implements Comp<A, R>, Asks {
    @Override public <B, Q extends R> Comp<B, Q> flatMap(Function<? super A, ? extends Comp<B, Q>> next) {
      return new Asking<>(op, next);
    }
  }
  /** An operation asked, and the program of its answer: the node a free monad is named for. */
  record Asking<X, A, R>(Op<X, ? super R> op, Function<? super X, ? extends Comp<A, R>> next)
      implements Comp<A, R>, Asks {}
  /** The two nodes that ask. */
  private sealed interface Asks { Op<?, ?> op(); }
  record FlatMap<X, A, R>(Comp<X, R> first, Function<? super X, ? extends Comp<A, R>> next) implements Comp<A, R> {}
  /** The part gives A and asks V; the handler is of an effect V has, gives B, and asks what stays, R. */
  record Part<A, B, V, R>(Handler<? super V, A, B, ? super R> handler, Comp<A, ? super V> part)
      implements Comp<B, R> {}
  record Resume<X, S, B, R>(K<X, S, B, R> k, X value, S state) implements Comp<B, R> {}
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
  private record Answer<X, F, S, B, R>(Op<X, F> op, X answer) implements Case<F, S, B, R> {}

  /** The others take the rest of the part off, as k. */
  private sealed interface Takes<F, S, B, R> extends Case<F, S, B, R> {}

  /** on(op, clause): when the clause ends, a rest it did not go on with was left, and is closed. */
  private record On<X, F, S, B, R>(Op<X, F> op, Clause<X, S, B, R> clause) implements Takes<F, S, B, R> {}

  /** keep(op, clause): the rest is the clause's to go on with later; nothing is closed when the clause ends. */
  private record Keep<X, F, S, B, R>(Op<X, F> op, Clause<X, S, B, R> clause) implements Takes<F, S, B, R> {}

  /** leave(value): of any operation of the effect, so with no answer's type; the rest is left. */
  private record Leave<F, S, B, R>(B value) implements Takes<F, S, B, R> {}

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
                                     Function<F, Case<F, S, B, R>> cases) implements Handler<F, A, B, R> {}

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
    while (steps != null) {
      try {
        switch (steps) {
          case Then step -> steps = step.after;
          case Mapping step -> steps = step.after;
          case Closing mark -> {
            steps = mark.after;
            mark.close.run();
          }
          case Taken mark -> {
            steps = mark.after;
            if (!mark.k.gone) mark.k.left(thrown);
          }
        }
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
    @SuppressWarnings("unchecked")
    public <V extends W, C> Handlers<R, V, A, C> handler(Handler<? super V, B, C, ? super R> handler) {
      Objects.requireNonNull(handler, "a handler");
      return new Handlers<>(part -> new Part<B, C, V, R>(handler, (Comp<B, ? super V>) around.apply(part)));
    }

    /** javac refuses a part that asks more than W. */
    @SuppressWarnings("unchecked")
    public Comp<B, R> handle(Comp<A, ? super W> part) {
      return (Comp<B, R>) around.apply(part);
    }
  }

  // ---- the machine: one loop; the rest of the program is a stack of parts, each a stack of steps ------------------

  /** The rest of a part: a step, and the steps after it. The value so far is given to a step, or passes a mark. */
  private sealed interface Rest {}

  /** flatMap: a program of the value so far. */
  private record Then(Function<Object, Comp<?, ?>> next, Rest after) implements Rest {}

  /** map: a value of it. */
  private record Mapping(Function<Object, Object> f, Rest after) implements Rest {}

  /** A mark of ensuring: when the value passes it, the part it was for has ended. */
  private record Closing(Runnable close, Rest after) implements Rest {}

  /** A mark under a clause: the rest that clause was given, to see when it ends whether it went on with it. */
  private record Taken(K<?, ?, ?, ?> k, Rest after) implements Rest {}

  /** A part being run: its handler, how many parts are around it, the steps after it, and the part around it. */
  private record Around(Fold<Object, Object, Object, Object, Object> handler, int depth, Rest rest, Around outer) {}

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
    Object[] states = {};                 //   each at its part's depth: an array never changed once it is shared,
    Object[] taken = null;                //   and written in place unless it is the one a rest was taken off with
    Op<?, ?> asked = null;                // the operation asked last
    try {
      turn: while (true) {
        Object value;
        switch (now) {
          case Pure<?, ?> v -> value = v.value();
          case FlatMap<?, ?, ?> m -> {
            rest = new Then((Function<Object, Comp<?, ?>>) (Function<?, ?>) m.next(), rest);
            now = m.first();
            continue;
          }
          case Mapped<?, ?, ?> m -> {
            rest = new Mapping((Function<Object, Object>) (Function<?, ?>) m.f(), rest);
            now = m.first();
            continue;
          }
          case Ensure<?, ?> e -> {
            rest = new Closing(e.after(), rest);
            now = e.part();
            continue;
          }
          case Part<?, ?, ?, ?> part -> {                     // a handler around a part of the program
            if (around != null) states = with(states, around.depth, state, states != taken);
            var handler = (Fold<Object, Object, Object, Object, Object>) (Handler<?, ?, ?, ?>) part.handler();
            around = new Around(handler, around == null ? 0 : around.depth + 1, rest, around);
            state = handler.state;
            rest = null;
            now = part.part();
            continue;
          }
          case Asks asks -> {
            Op<?, ?> op = asked = asks.op();
            if (see != null) see.accept(op);
            Around own = around;
            while (own != null && !own.handler.effect.isInstance(op)) own = own.outer;
            if (own == null) throw new IllegalStateException("no handler for " + op);
            switch (own.handler.cases.apply(op)) {
              case Answer<?, Object, Object, Object, Object> answered -> {
                value = made(answered.op, op, answered.answer);
                if (asks instanceof Asking<?, ?, ?> then) {            // what follows it, with no step put and taken
                  now = ((Function<Object, Comp<?, ?>>) (Function<?, ?>) then.next()).apply(value);
                  continue turn;
                }
              }
              case Takes<Object, Object, Object, Object> takes -> {   // the rest, to the end of the handler's part
                if (asks instanceof Asking<?, ?, ?> then) {
                  rest = new Then((Function<Object, Comp<?, ?>>) (Function<?, ?>) then.next(), rest);
                }
                K<Object, Object, Object, Object> k = new K<>(rest, around, own, states, state);
                Object its = own == around ? state : states[own.depth];
                Comp<?, ?> does = switch (takes) {
                  case On<?, Object, Object, Object, Object> on -> made(on.op, op, erased(on.clause)).on(its, k);
                  case Keep<?, Object, Object, Object, Object> keep ->
                      made(keep.op, op, erased(keep.clause)).on(its, k);
                  case Leave<Object, Object, Object, Object> leave -> new Pure<>(leave.value);
                };
                if (does instanceof Resume<?, ?, ?, ?> on && on.k() == k) {
                  // The clause only goes on with the rest: nothing was run between, so nothing is taken off or put
                  // back. The rest is not gone on with again (a clause of `on` does not keep it), so its states
                  // are no one's but the loop's: the next one is written where the last was.
                  if (takes instanceof Keep) taken = states;
                  if (own == around) state = on.state();
                  else states = with(states, own.depth, on.state(), states != taken);
                  value = on.value();
                } else {
                  taken = states;
                  // the clause is a program of what is around that part; under it, a mark: was the rest gone on with
                  rest = takes instanceof Keep ? own.rest : new Taken(k, own.rest);
                  around = own.outer;
                  if (around != null) state = states[around.depth];
                  now = does;
                  continue turn;
                }
              }
            }
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
          case Resume<?, ?, ?, ?> r -> {                      // the rest is put back, on top of what the clause is at
            K<?, ?, ?, ?> k = r.k();
            k.gone = true;
            if (rest instanceof Taken mark && mark.k == k) rest = mark.after;   // the clause ends with this: its mark
            int depth = around == null ? 0 : around.depth + 1;          // of the handler's own part, as put now
            int inside = k.top.depth - k.own.depth;                     // the parts the rest has inside that one
            if (around != null) states = with(states, around.depth, state, states != taken);
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
          case Defer<?, ?> d -> {
            now = d.program().get();
            continue;
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
          switch (rest) {
            case Then step -> {
              rest = step.after;
              now = step.next.apply(value);
              continue turn;
            }
            case Mapping step -> {
              rest = step.after;
              value = step.f.apply(value);
            }
            case Closing mark -> {
              rest = mark.after;
              mark.close.run();
            }
            case Taken mark -> {                        // its clause has ended: was the rest gone on with?
              rest = mark.after;
              if (!mark.k.gone) mark.k.left(null);
            }
          }
        }
      }
    } catch (Throwable thrown) {                        // what was to be closed is closed, and it is thrown on
      failed(thrown, asked, rest, around);
      throw thrown;
    }
  }

  /** What a case holds, for the operation asked: the case was made for that one, whose answer the rest takes. */
  private static <T> T made(Object made, Object asked, T held) {
    if (made != asked) throw new IllegalStateException("a clause made for " + made + ", asked was " + asked);
    return held;
  }

  /** A clause as the loop goes on with it: the answer's type, which only its case knew, is the loop's Object. */
  @SuppressWarnings("unchecked")
  private static Clause<Object, Object, Object, Object> erased(Clause<?, Object, Object, Object> clause) {
    return (Clause<Object, Object, Object, Object>) clause;
  }

  /** Where it failed is added to the exception, and every part still open is closed, the innermost first. */
  private static void failed(Throwable thrown, Object asked, Rest rest, Around around) {
    String in = "";
    for (Around part = around; part != null; part = part.outer) in += " " + part.handler.effect.getSimpleName();
    thrown.addSuppressed(new Where("the program had last asked " + asked + "; handlers in force, the nearest first:"
        + (in.isEmpty() ? " none" : in)));
    close(rest, thrown);
    for (Around part = around; part != null; part = part.outer) close(part.rest, thrown);
  }

  /**
   * The states with this one at this depth: the same array where it is there already, or where the array is the
   * loop's alone and has the room; else a copy, with room to grow — so parts put one in another cost the same each.
   */
  private static Object[] with(Object[] states, int depth, Object state, boolean alone) {
    if (depth < states.length) {
      if (states[depth] == state) return states;
      if (alone) {
        states[depth] = state;
        return states;
      }
    }
    Object[] next = Arrays.copyOf(states, Math.max(states.length, depth + 2 + (depth >> 1)));
    next[depth] = state;
    return next;
  }
}
