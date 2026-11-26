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
    return run(Fx.<Integer>top().handler(counting()).handler(first()).handler(clockAt(1))
        .handler(priced()).handler(stock(Map.of()))
        .handler(first()).handler(clockAt(2)).handle(count(N)));
  }
  @Benchmark public Object counterOutermostOfThree() {
    return run(Fx.<Integer>top().handler(first()).handler(clockAt(1)).handler(counting()).handle(count(N)));
  }
  @Benchmark public Object counterOutermostOfSeven() {
    return run(Fx.<Integer>top().handler(first()).handler(clockAt(1))
        .handler(priced()).handler(stock(Map.of())).handler(first())
        .handler(clockAt(2)).handler(counting()).handle(count(N)));
  }
  @Benchmark public Object answerInnermostOfSeven() {
    return run(Fx.<Long>top().handler(clockAt(1)).handler(first()).handler(counting())
        .handler(priced()).handler(stock(Map.of()))
        .handler(first()).handler(counting()).handle(ask(N, 0)));
  }
  @Benchmark public Object answerOutermostOfSeven() {
    return run(Fx.<Long>top().handler(first()).handler(counting())
        .handler(priced()).handler(stock(Map.of())).handler(first())
        .handler(counting()).handler(clockAt(1)).handle(ask(N, 0)));
  }
}
