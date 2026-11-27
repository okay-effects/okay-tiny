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
    assertEquals(new Final<>(10, "at 10"), run(Fx.<Final<Integer, String>>top().handler(state(0)).handle(counted())));
  }

  static <R extends Fail<String>> Comp<Integer, R> half(int n) {
    return n % 2 == 0 ? pure(n / 2) : fail("odd: " + n);
  }

  @Test
  void aFailure() {
    assertEquals(Optional.of(2), run(Fx.<Optional<Integer>>top().handler(option(String.class)).handle(half(4))));
    assertEquals(Optional.empty(), run(Fx.<Optional<Integer>>top().handler(option(String.class)).handle(half(3))));
    assertEquals(new Result.Ok<String, Integer>(2),
        run(Fx.<Result<String, Integer>>top().handler(result(String.class)).handle(half(4))));
    assertEquals(new Result.Failed<String, Integer>("odd: 3"),
        run(Fx.<Result<String, Integer>>top().handler(result(String.class)).handle(half(3))));
  }

  static <R extends Emit<String> & Env<Integer>> Comp<Integer, R> doubled() {
    return Std.<Integer, R>ask().flatMap(n -> Std.<String, R>tell("asked " + n)
        .flatMap(x -> Std.<String, R>tell("doubling")).map(x -> n * 2));
  }

  @Test
  void somethingToldAndSomethingGiven() {
    assertEquals(new Told<>(List.of("asked 21", "doubling"), 42),
        run(Fx.<Told<String, Integer>>top().handler(env(21)).handler(told(String.class)).handle(doubled())));
  }

  @Test
  void everyWay() {
    Comp<String, Choice> pairs = Std.<String, Choice>choose(List.of("a", "b")).flatMap(letter ->
        Std.<Integer, Choice>choose(List.of(1, 2)).map(digit -> letter + digit));
    assertEquals(List.of("a1", "a2", "b1", "b2"), run(Fx.<List<String>>top().handler(all()).handle(pairs)));
  }

  static <R extends State<Integer> & Fail<String>> Comp<String, R> countThenFail() {
    return Std.<Integer, R>update(n -> n + 1).flatMap(x -> fail("no"));
  }

  /** The failure inside the state: the state is kept. The state inside the failure: it is lost with the part. */
  @Test
  void theOrderOfTwoHandlersIsTheOrderOfTwoMonads() {
    Final<Integer, Result<String, String>> kept =
        run(Fx.<Final<Integer, Result<String, String>>>top().handler(state(0)).handler(result(String.class))
            .handle(countThenFail()));
    assertEquals(new Final<>(1, new Result.Failed<String, String>("no")), kept);
    Result<String, Final<Integer, String>> lost =
        run(Fx.<Result<String, Final<Integer, String>>>top().handler(result(String.class)).handler(state(0))
            .handle(countThenFail()));
    assertEquals(new Result.Failed<String, Final<Integer, String>>("no"), lost);
  }

  /** A state inside a choice is one for each way; outside it, one through them all. */
  @Test
  void aStateAndAChoice() {
    Comp<Integer, Object> each = Fx.<List<Final<Integer, Integer>>>top().handler(all()).handler(state(0))
        .handle(countedWay())
        .map(ways -> ways.stream().mapToInt(Final::state).sum());
    assertEquals(2, run(each));                                              // 1 and 1
    Comp<Integer, Object> through = Fx.<Final<Integer, List<Integer>>>top().handler(state(0)).handler(all())
        .handle(countedWay())
        .map(Final::state);
    assertEquals(2, run(through));                                           // 1, then 2
  }

  static <R extends State<Integer> & Choice> Comp<Integer, R> countedWay() {
    return Std.<Boolean, R>choose(List.of(true, false)).flatMap(way -> Std.<Integer, R>update(n -> n + 1))
        .flatMap(x -> Std.<Integer, R>get());
  }

  // ---- the usual shapes of a program

  static <R extends State<Integer> & Emit<String>> Comp<Integer, R> basket(List<String> items) {
    return Std.<String, R>forEach(items, StdTest::<R>take)
        .flatMap(x -> Std.<Integer, R>get())
        .flatMap(sum -> Std.<R>when(sum > 5, tell("heavy")).map(x -> sum));
  }

  static <R extends State<Integer> & Emit<String>> Comp<Void, R> take(String item) {
    return zip(tell("took " + item), update((Integer n) -> n + item.length()), (told, counted) -> null);
  }

  @Test
  void aLoopAndAnIf() {
    assertEquals(new Told<>(List.of("took tea", "took cup", "heavy"), new Final<>(6, 6)),
        run(Fx.<Told<String, Final<Integer, Integer>>>top().handler(told(String.class)).handler(state(0))
            .handle(basket(List.of("tea", "cup")))));
    assertEquals(new Told<>(List.of("took tea"), new Final<>(3, 3)),
        run(Fx.<Told<String, Final<Integer, Integer>>>top().handler(told(String.class)).handler(state(0))
            .handle(basket(List.of("tea")))));
  }

  /** The body's row may be less than the whole's: one that only tells, in a program that also has a state. */
  static <R extends Emit<String>> Comp<Integer, R> length(String word) {
    return Std.<String, R>tell(word).map(x -> word.length());
  }

  static <R extends State<Integer> & Emit<String>> Comp<String, R> lengths(List<String> words) {
    return zip(collect(words, StdTest::length), total(words), (each, sum) -> each + " = " + sum);
  }

  static <R extends State<Integer>> Comp<Integer, R> total(List<String> words) {
    return Std.<String, Integer, R>fold(words, 0, (sum, word) -> pure(sum + word.length()))
        .flatMap(sum -> Std.<Integer, R>put(sum).map(x -> sum));
  }

  @Test
  void whatEachGaveAndAStateCarriedThrough() {
    assertEquals(new Told<>(List.of("tea", "cup"), new Final<>(6, "[3, 3] = 6")),
        run(Fx.<Told<String, Final<Integer, String>>>top().handler(told(String.class)).handler(state(0))
            .handle(lengths(List.of("tea", "cup")))));
  }

  @Test
  void oneThenTheOtherAndAValueOfBoth() {
    Comp<String, Env<Integer>> both = zip(Std.<Integer, Env<Integer>>ask(), Fx.<String, Env<Integer>>pure("x"),
        (n, word) -> word + n);
    assertEquals("x7", run(Fx.<String>top().handler(env(7)).handle(both)));
  }

  /** Nothing of a body is run when the program is made; and a long loop takes no stack. */
  @Test
  void aLoopIsAProgramAndTakesNoStack() {
    var made = new java.util.ArrayList<Integer>();
    Comp<Integer, State<Integer>> counting = iterate(0, n -> n < 200_000, n -> {
      made.add(n);
      return Std.<Integer, State<Integer>>update(count -> count + 1).map(x -> n + 1);
    });
    assertEquals(List.of(), made);
    assertEquals(new Final<>(200_000, 200_000), run(Fx.<Final<Integer, Integer>>top().handler(state(0))
        .handle(counting)));

    List<Integer> many = java.util.stream.IntStream.range(0, 200_000).boxed().toList();
    Comp<List<Integer>, Object> doubled = collect(many, n -> pure(n * 2));
    assertEquals(399_998, run(doubled).get(199_999));
  }

  /** A loop under a rest gone on with twice: each way goes through the items from where it was, with its own. */
  @Test
  void aLoopInEveryWay() {
    Comp<List<String>, Choice> words = collect(List.of("a", "b"),
        letter -> Std.<Integer, Choice>choose(List.of(1, 2)).map(digit -> letter + digit));
    assertEquals(List.of(List.of("a1", "b1"), List.of("a1", "b2"), List.of("a2", "b1"), List.of("a2", "b2")),
        run(Fx.<List<List<String>>>top().handler(all()).handle(words)));
  }

  // ---- tail calls: a call that is the last thing a function of flatMap does is a step of the machine

  static <R extends State<Long>> Comp<Boolean, R> even(long n) {
    return n == 0 ? pure(true) : Std.<Long, R>update(count -> count + 1).flatMap(x -> odd(n - 1));
  }

  static <R extends State<Long>> Comp<Boolean, R> odd(long n) {
    return n == 0 ? pure(false) : Std.<Long, R>update(count -> count + 1).flatMap(x -> even(n - 1));
  }

  static <R> Comp<Boolean, R> evenAsking(long n) { return n == 0 ? pure(true) : defer(() -> oddAsking(n - 1)); }

  static <R> Comp<Boolean, R> oddAsking(long n) { return n == 0 ? pure(false) : defer(() -> evenAsking(n - 1)); }

  /** Two functions that call each other, five million times: neither the stack (256k) nor the heap (64m) grows. */
  @Test
  void aTailCallBetweenTwoFunctionsTakesNeitherStackNorHeap() {
    assertEquals(new Final<>(5_000_000L, true),
        run(Fx.<Final<Long, Boolean>>top().handler(state(0L)).handle(even(5_000_000))));
    assertEquals(false, run(StdTest.<Object>evenAsking(5_000_001)));
  }
}
