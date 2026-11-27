package okay.tiny;

import static okay.tiny.Fx.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** What the tests are written with: a few effects, programs that ask them, and their handlers. */
final class Shop {
  // ---- effects: a sealed interface; each operation a record that says the answer's type and its effect
  sealed interface Stock {
    record Reserve(String sku, int qty) implements Stock, Op<Boolean, Stock> {}
  }
  sealed interface Refusal {
    record Invalid(String field, String why) implements Refusal, Op<Void, Refusal> {}
  }
  sealed interface Clock {
    record Now() implements Clock, Op<Long, Clock> {}
  }
  sealed interface Mail {
    record Send(String to) implements Mail, Op<Void, Mail> {}
  }
  sealed interface Choice {
    record Flip() implements Choice, Op<Boolean, Choice> {}
  }
  sealed interface Counter {
    record Next() implements Counter, Op<Integer, Counter> {}
  }
  sealed interface Prices {
    record Price(String item) implements Prices, Op<Long, Prices> {}
    record Total(List<String> items) implements Prices, Op<Long, Prices> {}       // many at once: one operation
    record InStock(String item) implements Prices, Op<Boolean, Prices> {}
  }

  record Line(String sku, int qty) {}
  record Cart(String email, List<Line> lines) {}
  record Order(String email, int lines, long at) {}
  static final class BadRequest extends RuntimeException {
    private static final long serialVersionUID = 1;
    BadRequest(String m) { super(m); }
  }
  static final Map<String, Long> TABLE = Map.of("tea", 4L, "cup", 5L, "kettle", 111L);

  // ---- programs: the type says what a program may ask

  static <R extends Stock & Refusal> Comp<Integer, R> reserve(List<Line> lines, int from, int done) {
    if (from == lines.size()) return pure(done);
    Line l = lines.get(from);
    return perform(new Stock.Reserve(l.sku(), l.qty())).flatMap(ok ->
        ok ? reserve(lines, from + 1, done + 1)
            : perform(new Refusal.Invalid(l.sku(), "out of stock")).flatMap(x -> reserve(lines, from + 1, done)));
  }

  static <R extends Stock & Refusal & Clock> Comp<Order, R> place(Cart cart) {
    Comp<Void, R> email = cart.email() == null ? perform(new Refusal.Invalid("email", "required")) : pure(null);
    return email.flatMap(x -> reserve(cart.lines(), 0, 0)).flatMap(n ->
        perform(new Clock.Now()).map(at -> new Order(cart.email(), n, at)));
  }

  /** A handler inside a program: Refusal is taken out of the part's row; the handler says what the part gives. */
  static <R extends Stock & Clock> Comp<Optional<Order>, R> tried(Cart cart) {
    return Fx.<R, Optional<Order>>row().handler(trying()).handle(place(cart));
  }

  /** The effect stays in the row: its operations are seen here and asked again of the handler outside. */
  static <R extends Stock & Refusal & Clock> Comp<Order, R> traced(Cart cart, List<Object> trace) {
    return Fx.<R, Order>row()
        .handler(handling(Stock.class, op -> switch (op) {
          case Stock.Reserve r -> on(r, (s, k) -> {
            trace.add(r);
            return perform(r).flatMap(taken -> k.apply(taken, s));
          });
        }))
        .handle(place(cart));
  }

  static <R extends Mail & Clock> Comp<Long, R> twoLetters() {
    return perform(new Mail.Send("ann")).flatMap(a -> perform(new Mail.Send("bob")))
        .flatMap(b -> perform(new Clock.Now()));
  }

  /** The price of a cart: one operation that holds them all; the handler sees every item before it answers. */
  static <R extends Prices> Comp<Long, R> priceOf(List<String> cart) {
    return perform(new Prices.Total(cart));
  }

  record Checked(Order order, List<String> refused) {}

  /** The refusals are a state of the handler, a value; where the part ends it gives them with the part's value. */
  static <R extends Stock & Clock> Comp<Checked, R> placeAll(Cart cart) {
    return Fx.<R, Checked>row().handler(collecting()).handle(place(cart));
  }

  static <R extends Counter> Comp<Integer, R> count(int until) {
    return perform(new Counter.Next()).flatMap(i -> i >= until ? pure(i) : count(until));
  }

  // ---- handlers: a fold — a state, what it gives where the part ends, and a clause for each kind of operation

  static <B, R> Handler<Stock, B, B, R> stock(Map<String, Integer> left) {
    return handling(Stock.class, op -> switch (op) {
      case Stock.Reserve r -> answer(r, left.getOrDefault(r.sku(), 0) >= r.qty());
    });
  }
  static <B, R> Handler<Clock, B, B, R> clockAt(long t) {
    return handling(Clock.class, op -> switch (op) { case Clock.Now now -> answer(now, t); });
  }
  static <B, R> Handler<Refusal, B, B, R> first() {
    return handling(Refusal.class, op -> { switch (op) {
      case Refusal.Invalid i -> throw new BadRequest(i.field() + ": " + i.why()); }
    });
  }
  /** A fold with a state: the refusals so far; where the part ends, the last state and its value are the result. */
  static <R> Handler<Refusal, Order, Checked, R> collecting() {
    return folding(Refusal.class, List.<String>of(), (errors, order) -> new Checked(order, errors), op -> switch (op) {
      case Refusal.Invalid i -> on(i, (errors, k) ->
          k.apply(null, concat(errors, List.of(i.field() + ": " + i.why()))));
    });
  }
  /** A fold to an Optional: where the part ends with a value, that; where it refuses, none. */
  static <A, R> Handler<Refusal, A, Optional<A>, R> trying() {
    return folding(Refusal.class, null, (s, a) -> Optional.of(a), op -> switch (op) {
      case Refusal.Invalid i -> on(i, (s, k) -> pure(Optional.empty()));
    });
  }
  /** Any operation of Refusal leaves the part, with this value. */
  static <B, R> Handler<Refusal, B, B, R> leaving(B value) {
    return handling(Refusal.class, op -> switch (op) {
      case Refusal.Invalid i -> on(i, (s, k) -> pure(value));
    });
  }
  static <B, R> Handler<Counter, B, B, R> counting() {
    return folding(Counter.class, 0, (n, b) -> b, op -> switch (op) {
      case Counter.Next next -> on(next, (n, k) -> k.apply(n + 1, n + 1));
    });
  }
  /** A handler that itself asks: its clause is a program of the row that stays, here Clock. */
  static <B, R extends Clock> Handler<Mail, B, B, R> stamped(List<String> sent) {
    return handling(Mail.class, op -> switch (op) {
      case Mail.Send letter -> on(letter, (s, k) -> perform(new Clock.Now()).flatMap(at -> {
        sent.add(letter.to() + " at " + at);
        return k.apply(null, s);
      }));
    });
  }
  /** Two kinds of operation, two types of answer: a price is a Long, in stock a Boolean. */
  static <B, R> Handler<Prices, B, B, R> priced() {
    return handling(Prices.class, op -> switch (op) {
      case Prices.Price p -> answer(p, TABLE.get(p.item()));
      case Prices.InStock i -> answer(i, TABLE.containsKey(i.item()));
      case Prices.Total all -> answer(all, all.items().stream().mapToLong(TABLE::get).sum());
    });
  }
  /** The total of many items with one query for them all. */
  static <B, R> Handler<Prices, B, B, R> summed(List<String> queries) {
    return handling(Prices.class, op -> switch (op) {
      case Prices.Price p -> answer(p, TABLE.get(p.item()));
      case Prices.InStock i -> answer(i, TABLE.containsKey(i.item()));
      case Prices.Total all -> on(all, (s, k) -> {
        queries.add("all of " + all.items());
        return k.apply(all.items().stream().mapToLong(TABLE::get).sum(), s);
      });
    });
  }
  /** The same operation as ways to go: the rest goes on with each price in turn; the fold gives what each gave. */
  static <X, R> Handler<Prices, X, List<X>, R> eachWay() {
    return folding(Prices.class, null, (s, x) -> List.of(x), op -> switch (op) {
      case Prices.Price p -> answer(p, TABLE.get(p.item()));
      case Prices.InStock i -> answer(i, TABLE.containsKey(i.item()));
      case Prices.Total all -> on(all, (s, k) -> {
        Comp<List<X>, R> ways = pure(List.of());
        for (String item : all.items()) {
          Comp<List<X>, R> before = ways;
          ways = before.flatMap(xs -> k.apply(TABLE.get(item), s).map(ys -> concat(xs, ys)));
        }
        return ways;
      });
    });
  }
  /** The rest gone on with twice, with no replay: the handler's value is the list of what both ways gave. */
  static <X, R> Handler<Choice, X, List<X>, R> bothWays() {
    return folding(Choice.class, null, (s, x) -> List.of(x), op -> switch (op) {
      case Choice.Flip flip -> on(flip, (s, k) ->
          k.apply(true, s).flatMap(yes -> k.apply(false, s).map(no -> concat(yes, no))));
    });
  }

  static <X> List<X> concat(List<X> a, List<X> b) {
    var all = new ArrayList<>(a);
    all.addAll(b);
    return List.copyOf(all);
  }

  static Comp<Integer, Object> leavingLoop(int times, int acc) {
    if (times == 0) return pure(acc);
    return Fx.<Integer>top().handler(leaving(1))
        .handle(perform(new Refusal.Invalid("f", "w")).map(v -> 0))
        .flatMap(one -> leavingLoop(times - 1, acc + one));
  }

  static <R extends Choice & Counter> Comp<String, R> flipThenCount() {
    return perform(new Choice.Flip()).flatMap(way -> perform(new Counter.Next()).map(n -> way + " " + n));
  }

  static <R extends Choice & Counter & Clock & Mail> Comp<String, R> told() {
    return perform(new Choice.Flip()).flatMap(way -> perform(new Counter.Next()).flatMap(n ->
        perform(new Clock.Now()).flatMap(t -> perform(new Mail.Send(way + ":" + n + ":" + t)).flatMap(sent ->
            perform(new Counter.Next()).map(m -> way + " " + m)))));
  }

  static <B, R> Handler<Clock, B, B, R> ticking() {
    return folding(Clock.class, 0L, (t, b) -> b, op -> switch (op) {
      case Clock.Now now -> on(now, (t, k) -> k.apply(t + 1, t + 1));
    });
  }

  /** The letters sent are its state; where the part ends it gives them after the part's value. */
  static <A, R> Handler<Mail, A, String, R> logging() {
    return folding(Mail.class, List.<String>of(), (log, a) -> a + " " + log, op -> switch (op) {
      case Mail.Send letter -> on(letter, (log, k) -> k.apply(null, concat(log, List.of(letter.to()))));
    });
  }

  /** A clause that puts a handler of its own around the rest: the rest is put back one part deeper. */
  static <B, R> Handler<Mail, B, B, R> wrapping() {
    return handling(Mail.class, op -> switch (op) {
      case Mail.Send letter -> on(letter, (s, k) -> Fx.<R, B>row().handler(Shop.<B, R>first())
          .handle(k.apply(null, s)));
    });
  }

}
