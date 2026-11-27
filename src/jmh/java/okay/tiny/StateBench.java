package okay.tiny;

import static okay.tiny.Shop.*;
import static okay.tiny.Fx.*;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** ns an operation: a counter (a state that moves on at every operation) and a plain answer, at three places. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(StateBench.N)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 6, time = 1)
@Fork(1)
public class StateBench {
  static final int N = 10_000;

  static <R extends Clock> Comp<Long, R> ask(int left, long sum) {
    return left == 0 ? pure(sum) : perform(new Clock.Now()).flatMap(t -> ask(left - 1, sum + t));
  }

  static <R extends Clock> Comp<Long, R> mapped(int left, long sum) {
    return left == 0 ? pure(sum)
        : perform(new Clock.Now()).map(t -> t + 1).map(t -> t - 1).flatMap(t -> mapped(left - 1, sum + t));
  }

  @Benchmark public Object answerAndTwoMaps() {
    return run(Fx.<Long>top().handler(clockAt(1)).handle(mapped(N, 0)));
  }
  @Benchmark public Object counterAlone() {
    return run(Fx.<Integer>top().handler(counting()).handle(count(N)));
  }
  @Benchmark public Object counterInnermostOfSeven() {
    return run(Fx.<Integer>top().handler(clockAt(2)).handler(first()).handler(stock(Map.of()))
        .handler(priced()).handler(clockAt(1))
        .handler(first()).handler(counting()).handle(count(N)));
  }
  @Benchmark public Object counterOutermostOfThree() {
    return run(Fx.<Integer>top().handler(counting()).handler(clockAt(1)).handler(first()).handle(count(N)));
  }
  @Benchmark public Object counterOutermostOfSeven() {
    return run(Fx.<Integer>top().handler(counting()).handler(clockAt(2))
        .handler(first()).handler(stock(Map.of())).handler(priced())
        .handler(clockAt(1)).handler(first()).handle(count(N)));
  }
  @Benchmark public Object answerInnermostOfSeven() {
    return run(Fx.<Long>top().handler(counting()).handler(first()).handler(stock(Map.of()))
        .handler(priced()).handler(counting())
        .handler(first()).handler(clockAt(1)).handle(ask(N, 0)));
  }
  @Benchmark public Object answerOutermostOfSeven() {
    return run(Fx.<Long>top().handler(clockAt(1)).handler(counting())
        .handler(first()).handler(stock(Map.of())).handler(priced())
        .handler(counting()).handler(first()).handle(ask(N, 0)));
  }

  // ---- an effect said of Std's template, against the counter written by hand: one operation a turn, each a
  // clause that goes on with a new state

  interface Tick { Std.State<Integer, Tick> state = Std.State.of(Tick.class); }

  static <R extends Tick> Comp<Integer, R> ticks(int left) {
    return left == 0 ? Tick.state.get() : Tick.state.put(left).flatMap(done -> ticks(left - 1));
  }

  @Benchmark public Object templateAlone() {
    return run(Fx.<Std.Final<Integer, Integer>>top().handler(Tick.state.from(0)).handle(ticks(N)));
  }
  @Benchmark public Object templateOutermostOfSeven() {
    return run(Fx.<Std.Final<Integer, Integer>>top().handler(Tick.state.from(0)).handler(clockAt(2))
        .handler(first()).handler(stock(Map.of())).handler(priced())
        .handler(clockAt(1)).handler(first()).handle(ticks(N)));
  }

  // the template's program, with the effect written by hand and found by its class
  sealed interface Cell {
    record Get() implements Cell, Fx.Op<Integer, Cell> {}
    record Put(Integer state) implements Cell, Fx.Op<Void, Cell> {}
  }
  static <R extends Cell> Comp<Integer, R> cells(int left) {
    return left == 0 ? perform(new Cell.Get()) : perform(new Cell.Put(left)).flatMap(done -> cells(left - 1));
  }
  static <A, R> Fx.Handler<Cell, A, Std.Final<Integer, A>, R> cell(Integer initial) {
    return folding(Cell.class, initial, Std.Final::new, op -> switch (op) {
      case Cell.Get get -> on(get, (s, k) -> k.apply(s, s));
      case Cell.Put put -> on(put, (s, k) -> k.apply(null, put.state()));
    });
  }
  @Benchmark public Object byHandAlone() {
    return run(Fx.<Std.Final<Integer, Integer>>top().handler(cell(0)).handle(cells(N)));
  }

  // a wait for what is already there: a rest that is kept, and gone on with at once
  static Comp<Long, Std.Async> waits(int left, long sum) {
    return left == 0 ? pure(sum)
        : Std.<Long, Std.Async>await(() -> java.util.concurrent.CompletableFuture.completedFuture(1L))
            .flatMap(one -> waits(left - 1, sum + one));
  }
  @Benchmark public Object awaitDone() { return Std.start(waits(N, 0)).join(); }
}
