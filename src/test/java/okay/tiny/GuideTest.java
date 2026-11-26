package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.all;
import static okay.tiny.Std.choose;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Every example of docs/guide.md, compiled and run: the sections are in the guide's order. */
class GuideTest {
  // ---- 1. effects
  sealed interface Stock {
    record Reserve(String sku, int qty) implements Stock, Op<Boolean, Stock> {}
  }
  sealed interface Prices {
    record Price(String sku) implements Prices, Op<Long, Prices> {}
    record Total(List<String> skus) implements Prices, Op<Long, Prices> {}
  }
  sealed interface Refusal {
    record Invalid(String field, String why) implements Refusal, Op<Void, Refusal> {}
  }
  sealed interface Ids {
    record Next() implements Ids, Op<Integer, Ids> {}
  }
  sealed interface Clock {
    record Now() implements Clock, Op<Long, Clock> {}
  }
  sealed interface Mail {
    record Send(String to, String text) implements Mail, Op<Void, Mail> {}
  }

  record Line(String sku, int qty) {}
  record Cart(String email, List<Line> lines) {}
  record Order(int id, String email, long total, long at) {}

  // ---- 2. programs
  static <R extends Stock & Refusal> Comp<Void, R> reserve(Line line) {
    return perform(new Stock.Reserve(line.sku(), line.qty())).flatMap(ok ->
        ok ? pure(null) : perform(new Refusal.Invalid(line.sku(), "out of stock")));
  }

  static <R extends Stock & Refusal> Comp<Void, R> reserveAll(List<Line> lines, int from) {
    return from == lines.size() ? pure(null)
        : reserve(lines.get(from)).flatMap(done -> reserveAll(lines, from + 1));
  }

  static <R extends Stock & Refusal & Prices & Ids & Clock> Comp<Order, R> place(Cart cart) {
    Comp<Void, R> address = cart.email().contains("@") ? pure(null)
        : perform(new Refusal.Invalid("email", "not an address"));
    return address
        .flatMap(ok -> reserveAll(cart.lines(), 0))
        .flatMap(ok -> perform(new Prices.Total(cart.lines().stream().map(Line::sku).toList())))
        .flatMap(total -> perform(new Ids.Next()).flatMap(id ->
            perform(new Clock.Now()).map(at -> new Order(id, cart.email(), total, at))));
  }

  // ---- 3. handlers that only answer
  static <B, R> Handler<Stock, B, B, R> stock(Map<String, Integer> left) {
    return handling(Stock.class, op -> switch (op) {
      case Stock.Reserve r -> answer(r, left.getOrDefault(r.sku(), 0) >= r.qty());
    });
  }

  static <B, R> Handler<Prices, B, B, R> prices(Map<String, Long> table) {
    return handling(Prices.class, op -> switch (op) {
      case Prices.Price p -> answer(p, table.get(p.sku()));
      case Prices.Total t -> answer(t, t.skus().stream().mapToLong(table::get).sum());     // one query for all
    });
  }

  static <B, R> Handler<Clock, B, B, R> clockAt(long time) {
    return handling(Clock.class, op -> switch (op) { case Clock.Now now -> answer(now, time); });
  }

  // ---- 5. a handler in full: a state
  static <B, R> Handler<Ids, B, B, R> ids(int from) {
    return folding(Ids.class, from, (last, b) -> b, op -> switch (op) {
      case Ids.Next next -> on(next, (last, k) -> k.apply(last + 1, last + 1));
    });
  }

  // ---- 6. one effect, three meanings
  static <B, R> Handler<Refusal, B, B, R> refusing() {
    return handling(Refusal.class, op -> { switch (op) {
      case Refusal.Invalid i -> throw new IllegalArgumentException(i.field() + ": " + i.why()); }
    });
  }

  static <A, R> Handler<Refusal, A, Optional<A>, R> trying() {
    return folding(Refusal.class, null, (s, a) -> Optional.of(a), op -> leave(Optional.empty()));
  }

  record Checked<A>(A value, List<String> refused) {}

  static <A, R> Handler<Refusal, A, Checked<A>, R> collecting() {
    return folding(Refusal.class, List.<String>of(), (refused, a) -> new Checked<>(a, refused), op -> switch (op) {
      case Refusal.Invalid i -> on(i, (refused, k) -> k.apply(null, plus(refused, i.field() + ": " + i.why())));
    });
  }

  static List<String> plus(List<String> items, String item) {
    var more = new ArrayList<>(items);
    more.add(item);
    return List.copyOf(more);
  }

  final Map<String, Integer> left = Map.of("tea", 5, "cup", 1);
  final Map<String, Long> table = Map.of("tea", 4L, "cup", 5L, "pot", 30L);
  final Cart good = new Cart("ann@mail", List.of(new Line("tea", 2), new Line("cup", 1)));
  final Cart bad = new Cart("ann", List.of(new Line("tea", 9), new Line("pot", 1)));

  @Test
  void section4_theHandlersArePutAndTheProgramRun() {
    Order order = run(Fx.<Order>top()
        .handler(stock(left)).handler(prices(table)).handler(refusing()).handler(ids(100)).handler(clockAt(1700))
        .handle(place(good)));
    assertEquals(new Order(101, "ann@mail", 9, 1700), order);
  }

  @Test
  void section6_oneProgramThreeMeaningsOfARefusal() {
    var shop = Fx.<Order>top().handler(stock(left)).handler(prices(table)).handler(ids(100)).handler(clockAt(1700));
    // stop at the first: an exception
    IllegalArgumentException first = assertThrows(IllegalArgumentException.class,
        () -> run(shop.handler(refusing()).handle(place(bad))));
    assertEquals("email: not an address", first.getMessage());
    // no result
    assertEquals(Optional.empty(), run(shop.handler(trying()).handle(place(bad))));
    // all of them, and the program goes on
    Checked<Order> checked = run(shop.handler(collecting()).handle(place(bad)));
    assertEquals(List.of("email: not an address", "tea: out of stock", "pot: out of stock"), checked.refused());
    assertEquals(new Order(101, "ann", 34, 1700), checked.value());
  }

  // ---- 7. what a clause can do with the rest
  static <B, R> Handler<Clock, B, B, R> timed(List<String> log) {
    return handling(Clock.class, op -> switch (op) {
      case Clock.Now now -> on(now, (s, k) ->
          k.apply(1700L, s).map(result -> { log.add("the rest gave " + result); return result; }));
    });
  }

  @Test
  void section7_afterTheRest_andTwice() {
    var log = new ArrayList<String>();
    run(Fx.<Order>top().handler(stock(left)).handler(prices(table)).handler(refusing()).handler(ids(0))
        .handler(timed(log)).handle(place(good)));
    assertEquals(List.of("the rest gave Order[id=1, email=ann@mail, total=9, at=1700]"), log);

    // the rest gone on with for every option: Std's choice
    Comp<String, Std.Choice> outfit = Std.<String, Std.Choice>choose(List.of("tea", "cup")).flatMap(sku ->
        Std.<Integer, Std.Choice>choose(List.of(1, 2)).map(qty -> qty + " " + sku));
    assertEquals(List.of("1 tea", "2 tea", "1 cup", "2 cup"), run(Fx.<String>top().handler(all()).handle(outfit)));
  }

  // ---- 8. a handler that asks, and a handler that passes on
  static <B, R extends Clock> Handler<Mail, B, B, R> stamped(List<String> outbox) {
    return handling(Mail.class, op -> switch (op) {
      case Mail.Send letter -> on(letter, (s, k) -> perform(new Clock.Now()).flatMap(at -> {
        outbox.add(at + " " + letter.to() + ": " + letter.text());
        return k.apply(null, s);
      }));
    });
  }

  static <B, R extends Stock> Handler<Stock, B, B, R> audited(List<String> audit) {
    return handling(Stock.class, op -> switch (op) {
      case Stock.Reserve r -> on(r, (s, k) -> perform(r).flatMap(ok -> {       // asked again, of the handler outside
        audit.add(r.sku() + " x" + r.qty() + " -> " + ok);
        return k.apply(ok, s);
      }));
    });
  }

  static <R extends Mail & Stock & Refusal> Comp<String, R> confirm(Cart cart) {
    return reserveAll(cart.lines(), 0).flatMap(ok -> perform(new Mail.Send(cart.email(), "reserved")))
        .map(sent -> "confirmed");
  }

  /** A handler inside a program: Mail and the audit are answered here; Stock, Refusal and Clock stay in the row. */
  static <R extends Stock & Refusal & Clock> Comp<String, R> confirmed(Cart cart, List<String> outbox,
                                                                      List<String> audit) {
    return Fx.<R, String>row().handler(stamped(outbox)).handler(audited(audit)).handle(confirm(cart));
  }

  @Test
  void section8_aHandlerThatAsks_aHandlerThatPassesOn_aHandlerInsideAProgram() {
    var outbox = new ArrayList<String>();
    var audit = new ArrayList<String>();
    String done = run(Fx.<String>top().handler(stock(left)).handler(refusing()).handler(clockAt(1700))
        .handle(confirmed(good, outbox, audit)));
    assertEquals("confirmed", done);
    assertEquals(List.of("1700 ann@mail: reserved"), outbox);
    assertEquals(List.of("tea x2 -> true", "cup x1 -> true"), audit);
  }

  // ---- 9. an observer, 10. closing
  @Test
  void section9_anObserver_section10_closing() {
    var seen = new ArrayList<String>();
    var shop = Fx.<Order>top().handler(stock(left)).handler(prices(table)).handler(ids(0)).handler(clockAt(1700));
    run(shop.handler(refusing()).handle(place(good)), op -> seen.add(op.getClass().getSimpleName()));
    assertEquals(List.of("Reserve", "Reserve", "Total", "Next", "Now"), seen);

    var log = new ArrayList<String>();
    assertThrows(IllegalArgumentException.class,
        () -> run(shop.handler(refusing()).handle(ensuring(place(bad), () -> log.add("closed")))));
    assertEquals(List.of("closed"), log);
  }

  // ---- 12. an entry point of an application
  static final class Desk {
    private final Map<String, Integer> left;
    private final Map<String, Long> table;
    private int lastId;

    Desk(Map<String, Integer> left, Map<String, Long> table) { this.left = left; this.table = table; }

    Optional<Order> place(Cart cart) {
      return run(Fx.<Order>top()
          .handler(stock(left)).handler(prices(table)).handler(trying())
          .handler(handling(Ids.class, op -> switch (op) { case Ids.Next next -> answer(next, ++lastId); }))
          .handler(clockAt(System.currentTimeMillis()))
          .handle(GuideTest.place(cart)));
    }
  }

  @Test
  void section12_anEntryPoint() {
    Desk desk = new Desk(left, table);
    assertEquals(1, desk.place(good).orElseThrow().id());
    assertEquals(2, desk.place(good).orElseThrow().id());
    assertEquals(Optional.empty(), desk.place(bad));
  }
}
