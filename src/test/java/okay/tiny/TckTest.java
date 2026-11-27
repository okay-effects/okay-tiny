package okay.tiny;

import static okay.tiny.Fx.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The okay family's TCK (okay-effects, SPEC.md §12.3; its reference is scala/test/Tck.test.scala): a thousand random
 * programs — handlers of logs, states, failures and choices one in another in any order, each effect under two
 * identities — and the digest of their outcomes, which must be the reference machine's.
 *
 * <p>A handler here answers only the operations of its own identity and asks the others again outside: in okay-tiny
 * that is a clause like any other. A program may be left with an operation nobody answers; that is an outcome of
 * the TCK, so the programs are run past javac's check of the row, by one cast.
 */
class TckTest {
  sealed interface T {}
  record Pure(int n) implements T {}
  record Tell(int e, int n) implements T {}
  record Get(int e) implements T {}
  record Put(int e, int n) implements T {}
  record Among(List<Integer> ns) implements T {}
  record Raise(int e, int n) implements T {}
  record Seq(T a, T b) implements T {}
  record Add(T a, T b) implements T {}
  record WriterH(int e, T body) implements T {}
  record StateH(int e, int s0, T body) implements T {}
  record ThrowsH(int e, T body) implements T {}
  record ChooseH(T body) implements T {}

  /** The order of the draws is part of the definition. */
  static T gen(Random r, int depth) {
    if (depth <= 0 || r.nextInt(4) == 0) {
      return switch (r.nextInt(6)) {
        case 0 -> new Pure(r.nextInt(10));
        case 1 -> new Tell(r.nextInt(2), r.nextInt(10));
        case 2 -> new Get(r.nextInt(2));
        case 3 -> new Put(r.nextInt(2), r.nextInt(10));
        case 4 -> {
          List<Integer> ns = new ArrayList<>();
          for (int i = 1 + r.nextInt(2); i > 0; i--) ns.add(r.nextInt(10));
          yield new Among(ns);
        }
        default -> new Raise(r.nextInt(2), r.nextInt(10));
      };
    }
    return switch (r.nextInt(6)) {
      case 0 -> new Seq(gen(r, depth - 1), gen(r, depth - 1));
      case 1 -> new Add(gen(r, depth - 1), gen(r, depth - 1));
      case 2 -> new WriterH(r.nextInt(2), gen(r, depth - 1));
      case 3 -> new StateH(r.nextInt(2), r.nextInt(10), gen(r, depth - 1));
      case 4 -> new ThrowsH(r.nextInt(2), gen(r, depth - 1));
      default -> new ChooseH(gen(r, depth - 1));
    };
  }

  sealed interface Log {
    record TellOp(int e, int n) implements Log, Op<Void, Log> {}
  }

  sealed interface Store {
    record GetOp(int e) implements Store, Op<Integer, Store> {}
    record PutOp(int e, int n) implements Store, Op<Void, Store> {}
  }

  sealed interface Fail {
    record RaiseOp(int e, int n) implements Fail, Op<Integer, Fail> {}
  }

  sealed interface Choose {
    record AmongOp(List<Integer> values) implements Choose, Op<Integer, Choose> {}
  }

  /** Not its own: asked again outside, and the rest goes on with what that answered. */
  static <X, F, S, B, R extends F> Case<F, S, B, R> pass(Op<X, F> op) {
    return on(op, (state, k) -> Fx.<X, F, R>perform(op).flatMap(answer -> k.apply(answer, state)));
  }

  record Summed(int sum, int value) {}

  static <R extends Log> Comp<Integer, R> writer(int e, Comp<Integer, R> part) {
    Handler<Log, Integer, Summed, R> handler = folding(Log.class, null, (none, a) -> new Summed(0, a),
        op -> switch (op) {
          case Log.TellOp tell -> tell.e() != e ? pass(tell)
              : on(tell, (none, k) -> k.apply(null, null).map(l -> new Summed(l.sum() + tell.n(), l.value())));
        });
    return Fx.<R, Summed>row().handler(handler).handle(part).map(l -> l.value() * 100 + l.sum());
  }

  /**
   * The TCK's state is a cell made where the handler is put: one for every way of a rest gone on with past it —
   * not the state of a fold, which each way has its own.
   */
  static <R extends Store> Comp<Integer, R> state(int e, int s0, Comp<Integer, R> part) {
    int[] cell = {s0};
    Handler<Store, Integer, Integer, R> handler = folding(Store.class, null, (none, a) -> a * 10 + cell[0],
        op -> switch (op) {
          case Store.GetOp get -> get.e() != e ? pass(get) : on(get, (none, k) -> k.apply(cell[0], null));
          case Store.PutOp put -> put.e() != e ? pass(put) : on(put, (none, k) -> {
            cell[0] = put.n();
            return k.apply(null, null);
          });
        });
    return Fx.<R, Integer>row().handler(handler).handle(part);
  }

  static <R extends Fail> Comp<Integer, R> fails(int e, Comp<Integer, R> part) {
    Handler<Fail, Integer, Integer, R> handler = handling(Fail.class, op -> switch (op) {
      case Fail.RaiseOp raise -> raise.e() != e ? pass(raise) : on(raise, (none, k) -> pure(-1 - raise.n()));
    });
    return Fx.<R, Integer>row().handler(handler).handle(part);
  }

  /** Every way of a choice, the values summed. */
  static <R> Comp<Integer, R> chooses(Comp<Integer, ? super R> part) {
    Handler<Choose, Integer, Integer, R> handler = handling(Choose.class, op -> switch (op) {
      case Choose.AmongOp among -> on(among, (none, k) -> {
        Comp<Integer, R> sum = pure(0);
        for (Integer value : among.values()) {
          Comp<Integer, R> before = sum;
          sum = before.flatMap(so -> k.apply(value, null).map(more -> so + more));
        }
        return sum;
      });
    });
    return handled(handler, part);
  }

  @SuppressWarnings("unchecked")
  private static <R> Comp<Integer, R> handled(Handler<Choose, Integer, Integer, R> handler, Comp<Integer, ?> part) {
    return (Comp<Integer, R>) Fx.<Object, Integer>row().handler((Handler<Object, Integer, Integer, Object>) (Object)
        handler).handle((Comp<Integer, Object>) part);
  }

  /** Every program is of the one row of the four effects, whatever handlers it is under. */
  static <R extends Log & Store & Fail & Choose> Comp<Integer, R> build(T t) {
    return switch (t) {
      case Pure pure -> pure(pure.n());
      case Tell tell -> Fx.<Void, Log, R>perform(new Log.TellOp(tell.e(), tell.n())).map(done -> tell.n());
      case Get get -> perform(new Store.GetOp(get.e()));
      case Put put -> Fx.<Void, Store, R>perform(new Store.PutOp(put.e(), put.n())).map(done -> put.n());
      case Among among -> perform(new Choose.AmongOp(among.ns()));
      case Raise raise -> perform(new Fail.RaiseOp(raise.e(), raise.n()));
      case Seq seq -> TckTest.<R>build(seq.a()).flatMap(first -> build(seq.b()));
      case Add add -> TckTest.<R>build(add.a()).flatMap(i -> TckTest.<R>build(add.b()).map(j -> i + j));
      case WriterH w -> writer(w.e(), build(w.body()));
      case StateH s -> state(s.e(), s.s0(), build(s.body()));
      case ThrowsH f -> fails(f.e(), build(f.body()));
      case ChooseH c -> TckTest.<R>chooses(TckTest.<R>build(c.body()));
    };
  }

  /** Its value, or -1000000 where an operation is left with no handler. */
  @SuppressWarnings("unchecked")
  static <R extends Log & Store & Fail & Choose> int outcome(T t) {
    try {
      return run((Comp<Integer, Object>) (Comp<Integer, ?>) TckTest.<R>build(t));
    } catch (IllegalStateException left) {
      if (left.getMessage() != null && left.getMessage().startsWith("no handler for ")) return -1000000;
      throw left;
    }
  }

  static long digest() {
    Random r = new Random(20261010L);
    long d = 0;
    for (int i = 0; i < 1000; i++) {
      T t = gen(r, 6);
      t = new StateH(0, 0, new StateH(1, 0, new WriterH(0, new WriterH(1, t))));
      t = new ChooseH(new ThrowsH(0, new ThrowsH(1, t)));
      d = d * 31 + outcome(t);
    }
    return d;
  }

  @Test
  void theDigestIsTheFamilys() {
    assertEquals(-8623788747993822161L, digest());
  }
}
