package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Programs.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import okay.tiny.Fx;
import org.junit.jupiter.api.Test;

/** The programs of Programs, written in direct style, run under ordinary handlers on the ordinary machine. */
class DirectTest {
  static <B, R> Handler<Stock, B, B, R> stock(Map<String, Integer> left, Map<String, Integer> prices) {
    return handling(Stock.class, op -> switch (op) {
      case Stock.Reserve reserve -> answer(reserve, left.getOrDefault(reserve.sku(), 0) >= reserve.qty());
      case Stock.Price price -> answer(price, prices.get(price.sku()));
    });
  }

  static <A, R> Handler<Refusal, A, Optional<A>, R> refusing(List<String> why) {
    return folding(Refusal.class, null, (none, a) -> Optional.of(a), op -> switch (op) {
      case Refusal.Invalid invalid -> on(invalid, (none, k) -> {
        why.add(invalid.what());
        return pure(Optional.empty());
      });
    });
  }

  final Map<String, Integer> left = Map.of("tea", 5, "cup", 1), prices = Map.of("tea", 3, "cup", 10);

  Optional<Order> placed(List<Line> lines, List<String> why) {
    return run(Fx.<Order>top().handler(stock(left, prices)).handler(refusing(why)).handle(place(lines)));
  }

  @Test
  void aLoopAnIfAndAValue() {
    var why = new ArrayList<String>();
    assertEquals(Optional.of(new Order(List.of("tea", "cup"), 16)),
        placed(List.of(new Line("tea", 2), new Line("cup", 1)), why));
    assertEquals(List.of(), why);
  }

  /** The handler leaves the part at the first refusal: nothing after that $ is run. */
  @Test
  void theRestAfterADollarIsTheRestOfTheHandler() {
    var why = new ArrayList<String>();
    assertEquals(Optional.empty(), placed(List.of(new Line("tea", 9), new Line("pot", 1)), why));
    assertEquals(List.of("tea"), why);
    assertEquals(Optional.empty(), placed(List.of(), why));
    assertEquals(List.of("tea", "an empty cart"), why);
  }

  @Test
  void twoDollarsInOneExpressionAndNoTypesSaid() {
    assertEquals(new Told<>(List.of("counting tea"), new Final<>(13, 26)),
        run(Fx.<Integer>top().handler(state(10)).handler(told(String.class)).handle(counted("tea"))));
  }

  Told<String, Final<Integer, String>> branched(int n) {
    return run(Fx.<String>top().handler(state(7)).handler(told(String.class)).handle(branches(n)));
  }

  @Test
  void aBranchThatEndsTheMethodAndOnesThatGoOn() {
    assertEquals(new Told<>(List.of("negative"), new Final<>(7, "refused")), branched(-1));
    assertEquals(new Told<>(List.of("zero"), new Final<>(7, "state 7")), branched(0));
    assertEquals(new Told<>(List.of(), new Final<>(5, "state 5")), branched(5));
  }

  /** Two methods that call each other in the tail, a million times each: 256k of stack, 64m of heap. */
  @Test
  void aCallInTheTailTakesNeitherStackNorHeap() {
    assertEquals(new Final<>(2_000_000L, true), run(Fx.<Boolean>top().handler(state(0L)).handle(even(2_000_000))));
  }

  @Test
  void tryFinallyIsEnsuring() {
    var log = new ArrayList<String>();
    var why = new ArrayList<String>();
    assertEquals(Optional.of("done"), run(Fx.<String>top().handler(refusing(why)).handle(closing(log, false))));
    assertEquals(List.of("opened", "used", "closed"), log);
    log.clear();
    assertEquals(Optional.empty(), run(Fx.<String>top().handler(refusing(why)).handle(closing(log, true))));
    assertEquals(List.of("opened", "closed"), log);            // left at the $: closed, and "used" never run
  }

  /** The rest after a $ is a value: a handler goes on with it twice, and each way has its own variables. */
  @Test
  void everyWay() {
    assertEquals(List.of("a1", "a2", "b1", "b2"), run(Fx.<String>top().handler(all()).handle(pairs())));
  }

  @Test
  void nothingIsRunWhenTheProgramIsMade() {
    var log = new ArrayList<String>();
    Comp<Integer, Object> program = lazy(log);
    assertEquals(List.of(), log);
    assertEquals(1, run(program));
    assertEquals(1, run(program));
    assertEquals(List.of("run", "run"), log);
  }

  /** Without the plugin $ is an ordinary method, and says so. */
  @Test
  void aDollarOutsideADirectMethodSaysSo() {
    assertThrows(IllegalStateException.class, () -> Do.$(pure(1)));
  }
}
