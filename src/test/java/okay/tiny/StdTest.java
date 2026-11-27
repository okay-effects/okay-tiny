package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The usual effects under their handlers; and that the order of two handlers is the order of two monads. */
class StdTest {
  // The effects of this test, each said of one of Std's templates: a line.

  interface Count { State<Integer, Count> state = State.of(Count.class); }

  interface Other { State<Integer, Other> state = State.of(Other.class); }

  interface Steps { State<Long, Steps> state = State.of(Steps.class); }

  interface Note { State<String, Note> state = State.of(Note.class); }

  interface Odd { Fail<String, Odd> fail = Fail.of(Odd.class); }

  interface Log { Emit<String, Log> emit = Emit.of(Log.class); }

  interface Given { Env<Integer, Given> env = Env.of(Given.class); }

  static <R extends Count> Comp<String, R> counted() {
    return Count.state.update(n -> n + 1).flatMap(x -> Count.state.update(n -> n * 10))
        .flatMap(x -> Count.state.get()).map(n -> "at " + n);
  }

  @Test
  void aState() {
    assertEquals(new Final<>(10, "at 10"),
        run(Fx.<Final<Integer, String>>top().handler(Count.state.from(0)).handle(counted())));
  }

  static <R extends Odd> Comp<Integer, R> half(int n) {
    return n % 2 == 0 ? pure(n / 2) : Odd.fail.with("odd: " + n);
  }

  @Test
  void aFailure() {
    assertEquals(Optional.of(2), run(Fx.<Optional<Integer>>top().handler(Odd.fail.option()).handle(half(4))));
    assertEquals(Optional.empty(), run(Fx.<Optional<Integer>>top().handler(Odd.fail.option()).handle(half(3))));
    assertEquals(new Result.Ok<String, Integer>(2),
        run(Fx.<Result<String, Integer>>top().handler(Odd.fail.result()).handle(half(4))));
    assertEquals(new Result.Failed<String, Integer>("odd: 3"),
        run(Fx.<Result<String, Integer>>top().handler(Odd.fail.result()).handle(half(3))));
  }

  static <R extends Log & Given> Comp<Integer, R> doubled() {
    return Given.env.ask().flatMap(n -> Log.emit.tell("asked " + n)
        .flatMap(x -> Log.emit.tell("doubling")).map(x -> n * 2));
  }

  @Test
  void somethingToldAndSomethingGiven() {
    assertEquals(new Told<>(List.of("asked 21", "doubling"), 42),
        run(Fx.<Told<String, Integer>>top().handler(Given.env.given(21)).handler(Log.emit.told()).handle(doubled())));
  }

  @Test
  void everyWay() {
    Comp<String, Choice> pairs = Std.<String, Choice>choose(List.of("a", "b")).flatMap(letter ->
        Std.<Integer, Choice>choose(List.of(1, 2)).map(digit -> letter + digit));
    assertEquals(List.of("a1", "a2", "b1", "b2"), run(Fx.<List<String>>top().handler(all()).handle(pairs)));
  }

  static <R extends Count & Odd> Comp<String, R> countThenFail() {
    return Count.state.update(n -> n + 1).flatMap(x -> Odd.fail.with("no"));
  }

  /** The failure inside the state: the state is kept. The state inside the failure: it is lost with the part. */
  @Test
  void theOrderOfTwoHandlersIsTheOrderOfTwoMonads() {
    Final<Integer, Result<String, String>> kept =
        run(Fx.<Final<Integer, Result<String, String>>>top().handler(Count.state.from(0)).handler(Odd.fail.result())
            .handle(countThenFail()));
    assertEquals(new Final<>(1, new Result.Failed<String, String>("no")), kept);
    Result<String, Final<Integer, String>> lost =
        run(Fx.<Result<String, Final<Integer, String>>>top().handler(Odd.fail.result()).handler(Count.state.from(0))
            .handle(countThenFail()));
    assertEquals(new Result.Failed<String, Final<Integer, String>>("no"), lost);
  }

  /** A state inside a choice is one for each way; outside it, one through them all. */
  @Test
  void aStateAndAChoice() {
    Comp<Integer, Object> each = Fx.<List<Final<Integer, Integer>>>top().handler(all()).handler(Count.state.from(0))
        .handle(countedWay())
        .map(ways -> ways.stream().mapToInt(Final::state).sum());
    assertEquals(2, run(each));                                              // 1 and 1
    Comp<Integer, Object> through = Fx.<Final<Integer, List<Integer>>>top().handler(Count.state.from(0)).handler(all())
        .handle(countedWay())
        .map(Final::state);
    assertEquals(2, run(through));                                           // 1, then 2
  }

  static <R extends Count & Choice> Comp<Integer, R> countedWay() {
    return Std.<Boolean, R>choose(List.of(true, false)).flatMap(way -> Count.state.update(n -> n + 1))
        .flatMap(x -> Count.state.get());
  }

  // ---- the usual shapes of a program

  static <R extends Count & Log> Comp<Integer, R> basket(List<String> items) {
    return Std.<String, R>forEach(items, StdTest::<R>take)
        .flatMap(x -> Count.state.get())
        .flatMap(sum -> Std.<R>when(sum > 5, Log.emit.tell("heavy")).map(x -> sum));
  }

  static <R extends Count & Log> Comp<Void, R> take(String item) {
    return zip(Log.emit.tell("took " + item), Count.state.update(n -> n + item.length()), (told, counted) -> null);
  }

  @Test
  void aLoopAndAnIf() {
    assertEquals(new Told<>(List.of("took tea", "took cup", "heavy"), new Final<>(6, 6)),
        run(Fx.<Told<String, Final<Integer, Integer>>>top().handler(Log.emit.told()).handler(Count.state.from(0))
            .handle(basket(List.of("tea", "cup")))));
    assertEquals(new Told<>(List.of("took tea"), new Final<>(3, 3)),
        run(Fx.<Told<String, Final<Integer, Integer>>>top().handler(Log.emit.told()).handler(Count.state.from(0))
            .handle(basket(List.of("tea")))));
  }

  /** The body's row may be less than the whole's: one that only tells, in a program that also has a state. */
  static <R extends Log> Comp<Integer, R> length(String word) {
    return Log.emit.tell(word).map(x -> word.length());
  }

  static <R extends Count & Log> Comp<String, R> lengths(List<String> words) {
    return zip(collect(words, StdTest::length), total(words), (each, sum) -> each + " = " + sum);
  }

  static <R extends Count> Comp<Integer, R> total(List<String> words) {
    return Std.<String, Integer, R>fold(words, 0, (sum, word) -> pure(sum + word.length()))
        .flatMap(sum -> Count.state.put(sum).map(x -> sum));
  }

  @Test
  void whatEachGaveAndAStateCarriedThrough() {
    assertEquals(new Told<>(List.of("tea", "cup"), new Final<>(6, "[3, 3] = 6")),
        run(Fx.<Told<String, Final<Integer, String>>>top().handler(Log.emit.told()).handler(Count.state.from(0))
            .handle(lengths(List.of("tea", "cup")))));
  }

  @Test
  void oneThenTheOtherAndAValueOfBoth() {
    Comp<String, Given> both = zip(Given.env.ask(), Fx.<String, Given>pure("x"),
        (n, word) -> word + n);
    assertEquals("x7", run(Fx.<String>top().handler(Given.env.given(7)).handle(both)));
  }

  /** Nothing of a body is run when the program is made; and a long loop takes no stack. */
  @Test
  void aLoopIsAProgramAndTakesNoStack() {
    var made = new java.util.ArrayList<Integer>();
    Comp<Integer, Count> counting = iterate(0, n -> n < 200_000, n -> {
      made.add(n);
      return Count.state.update(count -> count + 1).map(x -> n + 1);
    });
    assertEquals(List.of(), made);
    assertEquals(new Final<>(200_000, 200_000), run(Fx.<Final<Integer, Integer>>top().handler(Count.state.from(0))
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

  static <R extends Steps> Comp<Boolean, R> even(long n) {
    return n == 0 ? pure(true) : Steps.state.update(count -> count + 1).flatMap(x -> odd(n - 1));
  }

  static <R extends Steps> Comp<Boolean, R> odd(long n) {
    return n == 0 ? pure(false) : Steps.state.update(count -> count + 1).flatMap(x -> even(n - 1));
  }

  static <R> Comp<Boolean, R> evenAsking(long n) { return n == 0 ? pure(true) : defer(() -> oddAsking(n - 1)); }

  static <R> Comp<Boolean, R> oddAsking(long n) { return n == 0 ? pure(false) : defer(() -> evenAsking(n - 1)); }

  /** Two functions that call each other, five million times: neither the stack (256k) nor the heap (64m) grows. */
  @Test
  void aTailCallBetweenTwoFunctionsTakesNeitherStackNorHeap() {
    assertEquals(new Final<>(5_000_000L, true),
        run(Fx.<Final<Long, Boolean>>top().handler(Steps.state.from(0L)).handle(even(5_000_000))));
    assertEquals(false, run(StdTest.<Object>evenAsking(5_000_001)));
  }

  // ---- each effect said of a template is a type of its own

  /** A method that knows nothing of its part's row puts a state of its own around the part, and uses it. */
  static <A, R> Comp<Final<String, A>, R> noted(Comp<A, R> part) {
    return Fx.<R, Final<String, A>>row().handler(Note.state.from(""))
        .handle(part.flatMap(a -> Note.state.put("gave " + a).map(done -> a)));
  }

  /**
   * The part has a state too, of another type, to be answered outside. The state `noted` put is between them,
   * and is another effect: it never sees the part's operations. (Were both one effect State, told apart only
   * by a type argument, it would answer the part's with a String for an Integer.)
   */
  @Test
  void aStatePutAroundAPartDoesNotAnswerThePartsOwn() {
    assertEquals(new Final<>(10, new Final<>("gave at 10", "at 10")),
        run(Fx.<Final<Integer, Final<String, String>>>top().handler(Count.state.from(0))
            .handle(noted(StdTest.<Count>counted()))));
  }

  static <R extends Count & Other> Comp<Integer, R> both() {
    return Count.state.update(n -> n + 1).flatMap(x -> Other.state.update(n -> n + 10))
        .flatMap(x -> zip(Count.state.get(), Other.state.get(), (a, b) -> a * 100 + b));
  }

  /** Two states of one type: two effects, two types in the row, and javac wants a handler of each. */
  @Test
  void twoStatesOfOneType() {
    assertEquals(new Final<>(1, new Final<>(10, 110)),
        run(Fx.<Final<Integer, Final<Integer, Integer>>>top().handler(Count.state.from(0))
            .handler(Other.state.from(0)).handle(both())));
  }

  interface Twice {}

  /** An effect is said of a template once: a second saying, which could be of another type, is refused. */
  @Test
  void anEffectIsSaidOfATemplateOnce() {
    State<Integer, Twice> first = State.of(Twice.class);
    IllegalStateException refused =
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> Fail.of(Twice.class));
    assertEquals(Twice.class.getName() + " is a state already", refused.getMessage());
  }
}
