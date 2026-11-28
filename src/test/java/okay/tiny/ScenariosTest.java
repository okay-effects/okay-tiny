package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** What the kernel does, a scenario each: the programs and the handlers are Shop's. */
class ScenariosTest {
  interface Level { Std.State<Integer, Level> state = Std.State.of(Level.class); }

  interface Given { Std.Env<Integer, Given> env = Std.Env.of(Given.class); }

  interface Said { Std.Emit<String, Said> emit = Std.Emit.of(Said.class); }

  final Map<String, Integer> left = Map.of("tea", 5, "cup", 1);
  final Cart good = new Cart("a@b.c", List.of(new Line("tea", 2), new Line("cup", 1)));
  final Cart bad = new Cart(null, List.of(new Line("tea", 9), new Line("cup", 1), new Line("pot", 1)));
  final List<String> cart = List.of("tea", "cup", "kettle");

  @Test
  void theHandlersArePutOnceAroundTheWholeProgram() {
    Order o = run(Fx.<Order>top().handler(clockAt(1000)).handler(first()).handler(stock(left))
        .handle(place(good)));
    assertEquals(new Order("a@b.c", 2, 1000), o);
  }

  @Test
  void theSameProgramTheFirstRefusalStopsIt() {
    BadRequest refused = assertThrows(BadRequest.class, () ->
        run(Fx.<Order>top().handler(clockAt(1)).handler(first()).handler(stock(left)).handle(place(bad))));
    assertEquals("email: required", refused.getMessage());
  }

  /** The refusals are the handler's state; where the part ends it gives them with the part's value. */
  @Test
  void theSameProgramAllRefusalsAreCollectedAndItGoesOn() {
    Checked all = run(Fx.<Checked>top().handler(clockAt(1)).handler(stock(left)).handle(placeAll(bad)));
    assertEquals(List.of("email: required", "tea: out of stock", "pot: out of stock"), all.refused());
    assertEquals(1, all.order().lines());
  }

  @Test
  void aPartIsLeftWithAValueOfTheHandlers() {
    var two = Fx.<Optional<Order>>top().handler(clockAt(9)).handler(stock(left));
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
        run(Fx.<List<String>>top().handler(bothWays()).handle(flips)));
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
    run(Fx.<Order>top().handler(clockAt(1)).handler(first()).handler(stock(left)).handle(traced(good, trace)));
    assertEquals(List.of(new Stock.Reserve("tea", 2), new Stock.Reserve("cup", 1)), trace);
  }

  /** The handler of Choice is outside the part that may be left: its rest has that part in it. */
  @Test
  void theRestIsTakenThroughAPartBetween() {
    Comp<String, Choice> flipInPart = Fx.<Choice, String>row()
        .handler(leaving("left"))
        .handle(perform(new Choice.Flip()).flatMap(a ->
            a ? pure("stayed") : perform(new Refusal.Invalid("f", "w")).map(v -> "never")));
    assertEquals(List.of("stayed", "left"), run(Fx.<List<String>>top().handler(bothWays()).handle(flipInPart)));
  }

  /** Many at once is an operation that holds them: what is done with them is the handler's. */
  @Test
  void oneOperationThatHoldsManyItems() {
    var queries = new ArrayList<String>();
    assertEquals(120, run(Fx.<Long>top().handler(summed(queries)).handle(priceOf(cart))));
    assertEquals(List.of("all of [tea, cup, kettle]"), queries);                // one query for them all
    assertEquals(List.of("costs 4", "costs 5", "costs 111"),                    // or the rest for each of them
        run(Fx.<List<String>>top().handler(eachWay()).handle(priceOf(cart).map(p -> "costs " + p))));
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
    Comp<List<String>, Object> inside = Fx.<List<String>>top().handler(bothWays())
        .handle(Fx.<Choice, String>row().handler(counting()).handle(flipThenCount()));
    assertEquals(List.of("true 1", "false 1"), run(inside));
    Comp<List<String>, Object> outside = Fx.<List<String>>top().handler(counting())
        .handle(Fx.<Counter, List<String>>row().handler(bothWays()).handle(flipThenCount()));
    assertEquals(List.of("true 1", "false 2"), run(outside));
  }

  @Test
  void theStatesOfSeveralHandlersAtOnce() {
    assertEquals("[true 2, false 2] [true:1:1, false:1:1]",                     // both inside the choice
        run(Fx.<String>top().handler(Shop.<List<String>, Object>logging()).handler(bothWays()).handler(counting())
            .handler(ticking())
            .handle(told())));
    assertEquals("[true 2, false 4] [true:1:1, false:3:1]",                     // the counter outside it
        run(Fx.<String>top().handler(Shop.<List<String>, Object>logging()).handler(counting()).handler(bothWays())
            .handler(ticking())
            .handle(told())));
    assertEquals("[true 2, false 4] [true:1:1, false:3:2]",                     // the clock too
        run(Fx.<String>top().handler(Shop.<List<String>, Object>logging()).handler(counting()).handler(ticking())
            .handler(bothWays())
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
    Comp<Optional<Order>, Object> tried = Fx.<Optional<Order>>top().handler(clockAt(1))
        .handler(stock(left)).handler(tryingAny())
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

  /** A part left, by leave or by a clause that does not go on: one closing that fails keeps no other from closing. */
  @Test
  void aClosingThatFailsKeepsNoOtherFromClosing() {
    Handler<Refusal, String, String, Object> notGoingOn = folding(Refusal.class, null, (s, a) -> a, op -> switch (op) {
      case Refusal.Invalid invalid -> on(invalid, (s, k) -> pure("left"));
    });
    Handler<Refusal, String, String, Object> leaves = folding(Refusal.class, null, (s, a) -> a, op -> leave("left"));
    for (Handler<Refusal, String, String, Object> handler : List.of(leaves, notGoingOn)) {
      var log = new ArrayList<String>();
      Comp<String, Refusal> opened = ensuring(
          ensuring(perform(new Refusal.Invalid("f", "w")).map(v -> "never"), () -> {
            log.add("inner");
            throw new IllegalStateException("the inner closing fails");
          }),
          () -> log.add("outer"));
      Comp<String, Object> around = ensuring(Fx.<String>top().handler(handler).handle(opened), () -> log.add("around"));
      assertEquals("the inner closing fails", assertThrows(IllegalStateException.class, () -> run(around)).getMessage());
      assertEquals(List.of("inner", "outer", "around"), log);
    }
  }

  /** Thrown on as it is, with a note of where the program was. */
  @Test
  void whatIsToBeClosedIsClosedUnderAnException() {
    var log = new ArrayList<String>();
    Comp<Order, Object> failing = Fx.<Order>top().handler(clockAt(1)).handler(first()).handler(stock(left))
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
    assertEquals(List.of("true", "false"), run(Fx.<List<String>>top().handler(bothWays()).handle(flip)));
    assertEquals(List.of("way true", "closed", "way false", "closed"), log);
  }

  /** The rest is gone on with and then, further on, left: closed once, by what leaves it. */
  @Test
  void aRestGoneOnWithAndThenLeftIsClosedOnce() {
    var log = new ArrayList<String>();
    Comp<String, Object> program = Fx.<String>top().handler(counting()).handler(leaving("left"))
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
    run(Fx.<Order>top().handler(clockAt(7)).handler(first()).handler(stock(left)).handle(place(good)), seen::add);
    assertEquals(List.of(new Stock.Reserve("tea", 2), new Stock.Reserve("cup", 1), new Clock.Now()), seen);
  }

  /** What the observer throws stops the program there: the operation is not answered, and nothing after it is asked. */
  @Test
  void anObserverRefusesAnOperation() {
    var seen = new ArrayList<Op<?, ?>>();
    IllegalStateException refused = assertThrows(IllegalStateException.class, () ->
        run(Fx.<Order>top().handler(clockAt(7)).handler(first()).handler(stock(left)).handle(place(good)), op -> {
          if (op instanceof Stock.Reserve r && r.sku().equals("cup")) throw new IllegalStateException("no cups today");
          seen.add(op);
        }));
    assertEquals("no cups today", refused.getMessage());
    assertEquals(List.of(new Stock.Reserve("tea", 2)), seen);
  }

  /** A handler that stops its part at each operation and gives the rest as a value: to go on with, later. */
  static <A> Handler<Clock, A, Supplier<A>, Object> paused(List<String> log) {
    return folding(Clock.class, null, (s, a) -> () -> a, op -> switch (op) {
      case Clock.Now now -> keep(now, (s, k) -> pure(() -> {
        log.add("going on");
        return run(k.apply(7L, s)).get();
      }));
    });
  }

  /** keep: the rest is gone on with after its clause, and the run it was taken in, have ended; and is closed once. */
  @Test
  void aRestKeptIsGoneOnWithLaterInARunOfItsOwn() {
    var log = new ArrayList<String>();
    Comp<Long, Clock> part = ensuring(perform(new Clock.Now()), () -> log.add("closed"));
    Supplier<Long> later = run(Fx.<Supplier<Long>>top().handler(paused(log)).handle(part.map(t -> t + 1)));
    assertEquals(List.of(), log);                       // the part has not ended: nothing is closed yet
    assertEquals(8L, later.get());
    assertEquals(List.of("going on", "closed"), log);
  }

  /**
   * A rest that is kept may be run inside its own clause, before that clause has ended: a callback given to work
   * already done is called there and then. The rest is whole from the moment the clause has it.
   */
  @Test
  void aRestKeptMayBeRunWithinItsOwnClause() {
    Handler<Clock, Long, Long, Object> atOnce = folding(Clock.class, null, (s, a) -> a, op -> switch (op) {
      case Clock.Now now -> keep(now, (s, k) -> pure(run(k.apply(7L, s))));
    });
    Comp<Long, Clock> part = perform(new Clock.Now()).flatMap(t -> perform(new Clock.Now()).map(u -> t + u));
    assertEquals(14L, run(Fx.<Long>top().handler(atOnce).handle(part)));
  }

  /** A state handler put anew at every level, and at the bottom a choice of the handler around them all. */
  static <R extends Std.Choice> Comp<Integer, R> deep(int level) {
    if (level == 0) return Std.choose(List.of(1, 2));
    Comp<Integer, R> inside = Fx.<R, Std.Final<Integer, Void>>row().handler(Level.state.<Void, R>from(level))
        .handle(Tower.level(level)).map(Std.Final::state);
    return inside.flatMap(sum -> Fx.<R, Integer>row().handler(Given.env.<Integer, R>given(sum))
        .handle(Given.env.ask()));                           // and a part more, put after the rest has gone on
  }

  interface Tower {
    static <R extends Std.Choice & Level> Comp<Void, R> level(int level) {
      return ScenariosTest.<R>deep(level - 1).flatMap(below -> Level.state.update(own -> own + below));
    }
  }

  /**
   * Parts one in another, three hundred deep, and the rest of them all gone on with twice: the second way finds the
   * states as they were when the choice was asked, whatever the first did to its own.
   */
  @Test
  void partsDeepOneInAnotherKeepTheirStatesOnEveryWay() {
    int levels = 300, sum = levels * (levels + 1) / 2;
    assertEquals(List.of(sum + 1, sum + 2), run(Fx.<List<Integer>>top().handler(Std.all()).handle(deep(levels))));
  }

  static <R> Comp<Long, R> under(int levels) {
    return levels == 0 ? pure(0L) : defer(() ->
        Fx.<R, Long>row().handler(Given.env.<Long, R>given(1)).handle(ScenariosTest.<R>under(levels - 1)));
  }

  /** And a part more costs the same however deep it is: three hundred thousand, one in another. */
  @Test
  void aPartMoreCostsTheSameHoweverDeep() {
    assertEquals(0L, assertTimeout(Duration.ofSeconds(20), () -> run(ScenariosTest.<Object>under(300_000))));
  }

  /** Three parts one in another, each with a state of its own, and nothing else. */
  static Comp<Integer, Object> threeParts() {
    Comp<Integer, Object> inner = Fx.<Std.Final<Integer, Integer>>top().handler(Level.state.from(300))
        .handle(Level.state.get()).map(Std.Final::state);
    Comp<Integer, Object> middle = Fx.<Std.Final<Integer, Integer>>top().handler(Level.state.from(200))
        .handle(inner).map(Std.Final::state);
    return Fx.<Std.Final<Integer, Integer>>top().handler(Level.state.from(100)).handle(middle).map(Std.Final::state);
  }

  /** A clock whose clause runs parts of its own before it goes on with the rest. */
  static <B> Handler<Clock, B, B, Object> busyClock() {
    return handling(Clock.class, op -> switch (op) {
      case Clock.Now now -> on(now, (s, k) -> threeParts().flatMap(done -> k.apply(7L, s)));
    });
  }

  static <R extends Clock & Said & Level> Comp<String, R> toldAroundTheClock() {
    return Said.emit.tell("before").flatMap(x -> perform(new Clock.Now()))
        .flatMap(at -> Said.emit.tell("at " + at)).map(x -> "done");
  }

  /** What a clause runs before it goes on does not reach the states of the rest it was given. */
  @Test
  void theStatesOfARestTakenOffAreItsOwn() {
    assertEquals(new Std.Told<>(List.of("before", "at 7"), new Std.Final<>(0, "done")),
        run(Fx.<Std.Told<String, Std.Final<Integer, String>>>top().handler(busyClock()).handler(Said.emit.told())
            .handler(Level.state.from(0))
            .handle(toldAroundTheClock())));
  }

  static <R extends Clock> Comp<Long, R> mapped(int left, long sum) {
    return left == 0 ? pure(sum)
        : perform(new Clock.Now()).map(t -> t + 1).map(t -> t - 1).flatMap(t -> mapped(left - 1, sum + t));
  }
}
