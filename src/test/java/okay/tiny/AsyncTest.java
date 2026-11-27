package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Asynchrony as an effect: one program, a handler that waits and one that does not. */
class AsyncTest {
  static final Key<Integer> COUNT = key("count");

  /** A service that answers later: each call is a stage the test ends when it likes. */
  static final class Prices {
    final List<String> asked = new ArrayList<>();
    final List<CompletableFuture<Integer>> pending = new ArrayList<>();

    Supplier<CompletionStage<Integer>> of(String item) {
      return () -> {
        asked.add(item);
        CompletableFuture<Integer> price = new CompletableFuture<>();
        pending.add(price);
        return price;
      };
    }
  }

  static Supplier<CompletionStage<Integer>> ready(int value) {
    return () -> CompletableFuture.completedFuture(value);
  }

  /** One price, then two at once. */
  static <R extends Async> Comp<Integer, R> total(Supplier<CompletionStage<Integer>> a,
                                                  Supplier<CompletionStage<Integer>> b,
                                                  Supplier<CompletionStage<Integer>> c) {
    return Std.<Integer, R>await(a).flatMap(first ->
        Std.<Integer, R>awaitAll(List.of(b, c)).map(two -> first + two.get(0) + two.get(1)));
  }

  @Test
  void theThreadThatRunsTheProgramWaits() {
    assertEquals(6, run(Fx.<Integer>top().handler(blocking()).handle(total(ready(1), ready(2), ready(3)))));
  }

  @Test
  void orNoThreadWaits() {
    Prices prices = new Prices();
    CompletableFuture<Integer> total = start(total(prices.of("a"), prices.of("b"), prices.of("c")));
    assertFalse(total.isDone());
    assertEquals(List.of("a"), prices.asked);

    prices.pending.get(0).complete(1);                        // the rest goes on, on the thread that ended it
    assertEquals(List.of("a", "b", "c"), prices.asked);       // many at once: both started before either is done
    assertFalse(total.isDone());

    prices.pending.get(2).complete(3);
    assertFalse(total.isDone());
    prices.pending.get(1).complete(2);
    assertEquals(6, total.join());
  }

  @Test
  void theRestGoesOnOnTheThreadThatEndedTheWork() throws Exception {
    Prices prices = new Prices();
    CompletableFuture<String> where = start(Std.<Integer, Async>await(prices.of("a"))
        .map(price -> Thread.currentThread().getName()));
    Thread other = new Thread(() -> prices.pending.get(0).complete(1), "the one that answered");
    other.start();
    assertEquals("the one that answered", where.get(5, TimeUnit.SECONDS));
  }

  /** The other handlers are in the program and go on with its rest: a state is the same state after a wait. */
  @Test
  void aStateGoesOnAcrossAWait() {
    Prices prices = new Prices();
    Comp<Integer, Async> counted = Fx.<Async, Final<Integer, Integer>>row().handler(state(COUNT, 10))
        .handle(countedPrice(prices.of("a")))
        .map(Final::state);
    CompletableFuture<Integer> state = start(counted);
    prices.pending.get(0).complete(5);
    assertEquals(16, state.join());
  }

  static <R extends Async & State<Integer>> Comp<Integer, R> countedPrice(Supplier<CompletionStage<Integer>> price) {
    return update(COUNT, n -> n + 1).flatMap(x -> Std.<Integer, R>await(price))
        .flatMap(p -> update(COUNT, n -> n + p)).map(x -> 0);
  }

  static Supplier<CompletionStage<Integer>> failing(RuntimeException failure) {
    return () -> CompletableFuture.failedFuture(failure);
  }

  /** What was to be closed is closed once, when the part has ended — not where the program first waited. */
  @Test
  void whatIsOpenStaysOpenAcrossAWait() {
    Prices prices = new Prices();
    List<String> log = new ArrayList<>();
    CompletableFuture<Integer> price = start(ensuring(Std.<Integer, Async>await(prices.of("a")),
        () -> log.add("closed")));
    assertEquals(List.of(), log);
    prices.pending.get(0).complete(7);
    assertEquals(7, price.join());
    assertEquals(List.of("closed"), log);
  }

  @Test
  void aFailureOfTheWorkIsThrownInTheProgramWhereItWaited() {
    IllegalStateException down = new IllegalStateException("down");
    List<String> log = new ArrayList<>();

    Comp<Integer, Async> part = ensuring(Std.<Integer, Async>await(failing(down)), () -> log.add("closed"));
    assertSame(down, assertThrows(IllegalStateException.class,
        () -> run(Fx.<Integer>top().handler(blocking()).handle(part))));
    assertEquals(List.of("closed"), log);

    Prices prices = new Prices();
    Comp<Integer, Async> later = ensuring(Std.<Integer, Async>await(prices.of("a")), () -> log.add("closed later"));
    CompletableFuture<Integer> failed = start(later);
    prices.pending.get(0).completeExceptionally(down);
    assertTrue(failed.isCompletedExceptionally());
    assertSame(down, assertThrows(CompletionException.class, failed::join).getCause());
    assertEquals(List.of("closed", "closed later"), log);

    assertSame(down, assertThrows(CompletionException.class, () -> start(part).join()).getCause());
  }

  /** Work that throws where it starts is work that failed. */
  @Test
  void aStartThatThrowsIsAFailureToo() {
    IllegalStateException down = new IllegalStateException("no connection");
    Supplier<CompletionStage<Integer>> broken = () -> { throw down; };
    assertSame(down, assertThrows(CompletionException.class, () -> start(Std.<Integer, Async>await(broken)).join())
        .getCause());
  }

  /** Work that is done already is not waited for: a long program of it takes no stack. */
  @Test
  void workThatIsDoneTakesNoStack() {
    assertEquals(200_000, start(AsyncTest.<Async>counting(0, 200_000)).join());
  }

  static <R extends Async> Comp<Integer, R> counting(int from, int to) {
    return from == to ? pure(from) : Std.<Integer, R>await(ready(from + 1)).flatMap(next -> counting(next, to));
  }

  @Test
  void theObserverSeesTheOperationsAfterAWaitToo() {
    Prices prices = new Prices();
    List<String> seen = new ArrayList<>();
    Comp<Integer, Async> program = Fx.<Async, Final<Integer, Integer>>row().handler(state(COUNT, 0))
        .handle(countedPrice(prices.of("a")))
        .map(Final::state);
    CompletableFuture<Integer> state = start(program, op -> seen.add(op.getClass().getSimpleName()));
    assertEquals(List.of("Get", "Put", "Await"), seen);
    prices.pending.get(0).complete(5);
    assertEquals(6, state.join());
    assertEquals(List.of("Get", "Put", "Await", "Get", "Put"), seen);
  }
}
