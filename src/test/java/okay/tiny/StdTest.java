package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The usual effects under their handlers; and that the order of two handlers is the order of two monads. */
class StdTest {
  static <R extends State<Integer>> Comp<String, R> counted() {
    return Std.<Integer, R>update(n -> n + 1).flatMap(x -> Std.<Integer, R>update(n -> n * 10))
        .flatMap(x -> Std.<Integer, R>get()).map(n -> "at " + n);
  }

  @Test
  void aState() {
    assertEquals(new Final<>(10, "at 10"), run(Fx.<String>top().handler(state(0)).handle(counted())));
  }

  static <R extends Fail<String>> Comp<Integer, R> half(int n) {
    return n % 2 == 0 ? pure(n / 2) : fail("odd: " + n);
  }

  @Test
  void aFailure() {
    assertEquals(Optional.of(2), run(Fx.<Integer>top().handler(option(String.class)).handle(half(4))));
    assertEquals(Optional.empty(), run(Fx.<Integer>top().handler(option(String.class)).handle(half(3))));
    assertEquals(new Result.Ok<String, Integer>(2),
        run(Fx.<Integer>top().handler(result(String.class)).handle(half(4))));
    assertEquals(new Result.Failed<String, Integer>("odd: 3"),
        run(Fx.<Integer>top().handler(result(String.class)).handle(half(3))));
  }

  static <R extends Emit<String> & Env<Integer>> Comp<Integer, R> doubled() {
    return Std.<Integer, R>ask().flatMap(n -> Std.<String, R>tell("asked " + n)
        .flatMap(x -> Std.<String, R>tell("doubling")).map(x -> n * 2));
  }

  @Test
  void somethingToldAndSomethingGiven() {
    assertEquals(new Told<>(List.of("asked 21", "doubling"), 42),
        run(Fx.<Integer>top().handler(told(String.class)).handler(env(21)).handle(doubled())));
  }

  @Test
  void everyWay() {
    Comp<String, Choice> pairs = Std.<String, Choice>choose(List.of("a", "b")).flatMap(letter ->
        Std.<Integer, Choice>choose(List.of(1, 2)).map(digit -> letter + digit));
    assertEquals(List.of("a1", "a2", "b1", "b2"), run(Fx.<String>top().handler(all()).handle(pairs)));
  }

  static <R extends State<Integer> & Fail<String>> Comp<String, R> countThenFail() {
    return Std.<Integer, R>update(n -> n + 1).flatMap(x -> fail("no"));
  }

  /** The failure inside the state: the state is kept. The state inside the failure: it is lost with the part. */
  @Test
  void theOrderOfTwoHandlersIsTheOrderOfTwoMonads() {
    Final<Integer, Result<String, String>> kept =
        run(Fx.<String>top().handler(result(String.class)).handler(state(0)).handle(countThenFail()));
    assertEquals(new Final<>(1, new Result.Failed<String, String>("no")), kept);
    Result<String, Final<Integer, String>> lost =
        run(Fx.<String>top().handler(state(0)).handler(result(String.class)).handle(countThenFail()));
    assertEquals(new Result.Failed<String, Final<Integer, String>>("no"), lost);
  }

  /** A state inside a choice is one for each way; outside it, one through them all. */
  @Test
  void aStateAndAChoice() {
    Comp<Integer, Object> each = Fx.<Integer>top().handler(state(0)).handler(all()).handle(countedWay())
        .map(ways -> ways.stream().mapToInt(Final::state).sum());
    assertEquals(2, run(each));                                              // 1 and 1
    Comp<Integer, Object> through = Fx.<Integer>top().handler(all()).handler(state(0)).handle(countedWay())
        .map(Final::state);
    assertEquals(2, run(through));                                           // 1, then 2
  }

  static <R extends State<Integer> & Choice> Comp<Integer, R> countedWay() {
    return Std.<Boolean, R>choose(List.of(true, false)).flatMap(way -> Std.<Integer, R>update(n -> n + 1))
        .flatMap(x -> Std.<Integer, R>get());
  }
}
