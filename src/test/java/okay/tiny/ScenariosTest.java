package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** What the kernel does, a scenario each: the programs and the handlers are Shop's. */
class ScenariosTest {
  final Map<String, Integer> left = Map.of("tea", 5, "cup", 1);
  final Cart good = new Cart("a@b.c", List.of(new Line("tea", 2), new Line("cup", 1)));
  final Cart bad = new Cart(null, List.of(new Line("tea", 9), new Line("cup", 1), new Line("pot", 1)));
  final List<String> cart = List.of("tea", "cup", "kettle");

  @Test
  void theHandlersArePutOnceAroundTheWholeProgram() {
    Order o = run(Fx.<Order>top().handler(stock(left)).handler(first()).handler(clockAt(1000))
        .handle(place(good)));
    assertEquals(new Order("a@b.c", 2, 1000), o);
  }

  @Test
  void theSameProgramTheFirstRefusalStopsIt() {
    BadRequest refused = assertThrows(BadRequest.class, () ->
        run(Fx.<Order>top().handler(stock(left)).handler(first()).handler(clockAt(1)).handle(place(bad))));
    assertEquals("email: required", refused.getMessage());
  }

  /** The refusals are the handler's state; where the part ends it gives them with the part's value. */
  @Test
  void theSameProgramAllRefusalsAreCollectedAndItGoesOn() {
    Checked all = run(Fx.<Checked>top().handler(stock(left)).handler(clockAt(1)).handle(placeAll(bad)));
    assertEquals(List.of("email: required", "tea: out of stock", "pot: out of stock"), all.refused());
    assertEquals(1, all.order().lines());
  }

  @Test
  void aPartIsLeftWithAValueOfTheHandlers() {
    var two = Fx.<Optional<Order>>top().handler(stock(left)).handler(clockAt(9));
    assertEquals(Optional.of(new Order("a@b.c", 2, 9)), run(two.handle(tried(good))));
    assertEquals(Optional.empty(), run(two.handle(tried(bad))));
  }

  /** What a clause does after the rest is before what the program does after the clause's part. */
  @Test
  void aClauseDoesSomethingWithTheValueOfTheRest() {
    var order = new ArrayList<String>();
    Comp<Long, Object> partThenMore = Fx.<Long>top()
        .handler(handling(Clock.class, op -> switch (op) {
          case Clock.Now now -> on(now, (s, k) ->
              k.apply(1L, s).map(t -> { order.add("after the rest: " + t); return t + 1; }));
        }))
        .handle(perform(new Clock.Now()))
        .map(t -> { order.add("after the part: " + t); return t; });
    assertEquals(2L, run(partThenMore));
    assertEquals(List.of("after the rest: 1", "after the part: 2"), order);
  }

  /** With no replay: the rest is a value. The handler's own value is the list of what each way gave. */
  @Test
  void theRestIsGoneOnWithTwice() {
    Comp<String, Choice> flips = perform(new Choice.Flip()).flatMap(a ->
        perform(new Choice.Flip()).map(b -> a + "," + b));
    assertEquals(List.of("true,true", "true,false", "false,true", "false,false"),
        run(Fx.<String>top().handler(bothWays()).handle(flips)));
  }

  @Test
  void aClauseAsksAnotherEffect() {
    var sent = new ArrayList<String>();
    long at = run(Fx.<Long>top().handler(clockAt(5)).handle(
        Fx.<Clock, Long>row().handler(stamped(sent)).handle(twoLetters())));
    assertEquals(List.of("ann at 5", "bob at 5"), sent);
    assertEquals(5, at);
  }

  /** A handler that asks the operation again, of the handler outside: every operation of the effect, as data. */
  @Test
  void everyOperationOfAnEffectIsSeen() {
    var trace = new ArrayList<Object>();
    run(Fx.<Order>top().handler(stock(left)).handler(first()).handler(clockAt(1)).handle(traced(good, trace)));
    assertEquals(List.of(new Stock.Reserve("tea", 2), new Stock.Reserve("cup", 1)), trace);
  }

  /** The handler of Choice is outside the part that may be left: its rest has that part in it. */
  @Test
  void theRestIsTakenThroughAPartBetween() {
    Comp<String, Choice> flipInPart = Fx.<Choice, String>row()
        .handler(leaving("left"))
        .handle(perform(new Choice.Flip()).flatMap(a ->
            a ? pure("stayed") : perform(new Refusal.Invalid("f", "w")).map(v -> "never")));
    assertEquals(List.of("stayed", "left"), run(Fx.<String>top().handler(bothWays()).handle(flipInPart)));
  }

  /** Many at once is an operation that holds them: what is done with them is the handler's. */
  @Test
  void oneOperationThatHoldsManyItems() {
    var queries = new ArrayList<String>();
    assertEquals(120, run(Fx.<Long>top().handler(summed(queries)).handle(priceOf(cart))));
    assertEquals(List.of("all of [tea, cup, kettle]"), queries);                // one query for them all
    assertEquals(List.of("costs 4", "costs 5", "costs 111"),                    // or the rest for each of them
        run(Fx.<String>top().handler(eachWay()).handle(priceOf(cart).map(p -> "costs " + p))));
  }

  @Test
  void theAnswerIsOfTheOperationsOwnType() {
    Comp<String, Prices> twoKinds = perform(new Prices.Price("tea")).flatMap(price ->
        perform(new Prices.InStock("pot")).map(in -> price + " " + in));          // a Long, then a Boolean
    assertEquals("4 false", run(Fx.<String>top().handler(priced()).handle(twoKinds)));
  }

  @Test
  void aStateGoesOnThroughAPartInside() {
    Comp<String, Counter> counted = perform(new Counter.Next()).flatMap(a ->
        Fx.<Counter, Integer>row().handler(leaving(-1)).handle(perform(new Counter.Next())).flatMap(b ->
            perform(new Counter.Next()).map(c -> a + " " + b + " " + c)));
    assertEquals("1 2 3", run(Fx.<String>top().handler(counting()).handle(counted)));
  }

  /** The handler of Mail is inside the clock's part and asks it: the clock's state goes on through that clause. */
  @Test
  void aStateGoesOnThroughAClauseThatAsksIt() {
    var letters = new ArrayList<String>();
    Comp<Long, Object> ticked = Fx.<Long>top().handler(ticking())
        .handle(Fx.<Clock, Long>row().handler(stamped(letters)).handle(
            perform(new Mail.Send("ann")).flatMap(x -> perform(new Mail.Send("bob")))
                .flatMap(x -> perform(new Clock.Now()))));
    assertEquals(3, run(ticked));
    assertEquals(List.of("ann at 1", "bob at 2"), letters);
  }

  /** A rest gone on with twice: a state inside the choice is one for each way, outside it one through both. */
  @Test
  void aStateIsAValueOfEachWay() {
    Comp<List<String>, Object> inside = Fx.<String>top().handler(bothWays())
        .handle(Fx.<Choice, String>row().handler(counting()).handle(flipThenCount()));
    assertEquals(List.of("true 1", "false 1"), run(inside));
    Comp<List<String>, Object> outside = Fx.<List<String>>top().handler(counting())
        .handle(Fx.<Counter, String>row().handler(bothWays()).handle(flipThenCount()));
    assertEquals(List.of("true 1", "false 2"), run(outside));
  }

  @Test
  void theStatesOfSeveralHandlersAtOnce() {
    assertEquals("[true 2, false 2] [true:1:1, false:1:1]",                     // both inside the choice
        run(Fx.<String>top().handler(ticking()).handler(counting()).handler(bothWays()).handler(logging())
            .handle(told())));
    assertEquals("[true 2, false 4] [true:1:1, false:3:1]",                     // the counter outside it
        run(Fx.<String>top().handler(ticking()).handler(bothWays()).handler(counting()).handler(logging())
            .handle(told())));
    assertEquals("[true 2, false 4] [true:1:1, false:3:2]",                     // the clock too
        run(Fx.<String>top().handler(bothWays()).handler(ticking()).handler(counting()).handler(logging())
            .handle(told())));
  }

  /** A clause that puts a handler of its own around the rest: the rest goes back one part deeper than it was. */
  @Test
  void aRestIsPutBackDeeperThanItWasTaken() {
    Comp<String, Object> shifted = Fx.<String>top().handler(counting()).handler(wrapping()).handler(counting())
        .handle(perform(new Counter.Next()).flatMap(a -> perform(new Mail.Send("x")).flatMap(x ->
            perform(new Counter.Next()).flatMap(b -> perform(new Counter.Next()).map(c -> a + " " + b + " " + c)))));
    assertEquals("1 2 3", run(shifted));
  }

  /** The machine is a loop: this runs with -Xss256k -Xmx64m (build.gradle.kts). */
  @Test
  void aLongProgramTakesNoStack() {
    assertEquals(1_000_000, run(Fx.<Integer>top().handler(counting()).handle(count(1_000_000))));
    Comp<Integer, Counter> chain = pure(0);
    for (int i = 0; i < 1_000_000; i++) chain = chain.flatMap(x -> perform(new Counter.Next()));
    assertEquals(1_000_000, run(Fx.<Integer>top().handler(counting()).handle(chain)));
    assertEquals(1_000_000, run(leavingLoop(1_000_000, 0)));
    assertTrue(run(Fx.<Long>top().handler(clockAt(1)).handle(mapped(1_000_000, 0))) == 1_000_000);
  }

  /** No switch: whatever operation of the effect is asked, the part is left with the value. */
  @Test
  void aClauseForAnyOperationLeavesThePart() {
    Comp<Optional<Order>, Object> tried = Fx.<Order>top().handler(tryingAny())
        .handler(stock(left)).handler(clockAt(1))
        .handle(place(bad));
    assertEquals(Optional.empty(), run(tried));
  }

  @Test
  void whatIsToBeClosedIsClosedWhenThePartEnds() {
    var log = new ArrayList<String>();
    Comp<String, Object> inOrder = ensuring(
        ensuring(Fx.<String, Object>pure("in").map(x -> { log.add(x); return x; }), () -> log.add("inner closed")),
        () -> log.add("outer closed")).map(x -> { log.add("after"); return x; });
    assertEquals("in", run(inOrder));
    assertEquals(List.of("in", "inner closed", "outer closed", "after"), log);
  }

  /** A clause that does not go on with the rest: what the rest was to close is closed when that clause ends. */
  @Test
  void whatIsToBeClosedIsClosedWhenThePartIsLeft() {
    var log = new ArrayList<String>();
    Comp<String, Refusal> opened = ensuring(
        perform(new Refusal.Invalid("f", "w")).map(v -> { log.add("never"); return "never"; }),
        () -> log.add("closed"));
    Comp<String, Object> left = Fx.<String>top().handler(leaving("left")).handle(opened)
        .map(x -> { log.add("after"); return x; });
    assertEquals("left", run(left));
    assertEquals(List.of("closed", "after"), log);
  }

  /** Thrown on as it is, with a note of where the program was. */
  @Test
  void whatIsToBeClosedIsClosedUnderAnException() {
    var log = new ArrayList<String>();
    Comp<Order, Object> failing = Fx.<Order>top().handler(stock(left)).handler(first()).handler(clockAt(1))
        .handle(ensuring(ensuring(place(bad), () -> log.add("inner closed")), () -> log.add("outer closed")));
    BadRequest thrown = assertThrows(BadRequest.class, () -> run(failing));
    assertEquals(List.of("inner closed", "outer closed"), log);
    assertEquals(1, thrown.getSuppressed().length);
    assertEquals("the program had last asked Invalid[field=email, why=required]; handlers in force, the nearest first:"
        + " Stock Refusal Clock", thrown.getSuppressed()[0].getMessage());
  }

  /** A rest gone on with twice ends twice; one gone on with once and one left: once each. */
  @Test
  void aRestGoneOnWithTwiceIsClosedTwice() {
    var log = new ArrayList<String>();
    Comp<String, Choice> flip = ensuring(
        perform(new Choice.Flip()).map(way -> { log.add("way " + way); return "" + way; }), () -> log.add("closed"));
    assertEquals(List.of("true", "false"), run(Fx.<String>top().handler(bothWays()).handle(flip)));
    assertEquals(List.of("way true", "closed", "way false", "closed"), log);
  }

  /** The rest is gone on with and then, further on, left: closed once, by what leaves it. */
  @Test
  void aRestGoneOnWithAndThenLeftIsClosedOnce() {
    var log = new ArrayList<String>();
    Comp<String, Object> program = Fx.<String>top().handler(leaving("left")).handler(counting())
        .handle(ensuring(
            perform(new Counter.Next()).flatMap(n -> perform(new Refusal.Invalid("f", "w"))).map(v -> "never"),
            () -> log.add("closed")));
    assertEquals("left", run(program));
    assertEquals(List.of("closed"), log);
  }

  static <A, R> Handler<Refusal, A, Optional<A>, R> tryingAny() {
    return folding(Refusal.class, null, (s, a) -> Optional.of(a), op -> leave(Optional.empty()));
  }

  /** The second argument of run: every operation asked, of every effect, in the order asked. */
  @Test
  void anObserverIsGivenEveryOperation() {
    var seen = new ArrayList<Op<?, ?>>();
    run(Fx.<Order>top().handler(stock(left)).handler(first()).handler(clockAt(7)).handle(place(good)), seen::add);
    assertEquals(List.of(new Stock.Reserve("tea", 2), new Stock.Reserve("cup", 1), new Clock.Now()), seen);
  }

  /** What the observer throws stops the program there: the operation is not answered, and nothing after it is asked. */
  @Test
  void anObserverRefusesAnOperation() {
    var seen = new ArrayList<Op<?, ?>>();
    IllegalStateException refused = assertThrows(IllegalStateException.class, () ->
        run(Fx.<Order>top().handler(stock(left)).handler(first()).handler(clockAt(7)).handle(place(good)), op -> {
          if (op instanceof Stock.Reserve r && r.sku().equals("cup")) throw new IllegalStateException("no cups today");
          seen.add(op);
        }));
    assertEquals("no cups today", refused.getMessage());
    assertEquals(List.of(new Stock.Reserve("tea", 2)), seen);
  }

  static <R extends Clock> Comp<Long, R> mapped(int left, long sum) {
    return left == 0 ? pure(sum)
        : perform(new Clock.Now()).map(t -> t + 1).map(t -> t - 1).flatMap(t -> mapped(left - 1, sum + t));
  }
}
