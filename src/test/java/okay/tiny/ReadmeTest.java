package okay.tiny;

import static okay.tiny.Fx.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The example of README.md, compiled and run. */
class ReadmeTest {
  sealed interface Stock {
    record Reserve(String sku, int qty) implements Stock, Op<Boolean, Stock> {}
  }
  sealed interface Refusal {
    record Invalid(String field, String why) implements Refusal, Op<Void, Refusal> {}
  }

  record Cart(String sku, int qty) {}
  record Order(Cart cart) {}

  static <R extends Stock & Refusal> Comp<Order, R> place(Cart cart) {
    return perform(new Stock.Reserve(cart.sku(), cart.qty())).flatMap(ok ->
        ok ? pure(new Order(cart))
            : perform(new Refusal.Invalid(cart.sku(), "out of stock")).map(x -> new Order(cart)));
  }

  static <B, R> Handler<Stock, B, B, R> stock(Map<String, Integer> left) {
    return handling(Stock.class, op -> switch (op) {
      case Stock.Reserve r -> answer(r, left.getOrDefault(r.sku(), 0) >= r.qty());
    });
  }

  static <A, R> Handler<Refusal, A, Optional<A>, R> trying() {
    return folding(Refusal.class, null, (s, a) -> Optional.of(a), op -> switch (op) {
      case Refusal.Invalid i -> on(i, (s, k) -> pure(Optional.empty()));       // the rest is left
    });
  }

  @Test
  void theExample() {
    var left = Map.of("tea", 5);
    Cart tea = new Cart("tea", 2), pot = new Cart("pot", 1);
    Optional<Order> o = run(Fx.<Order>top().handler(trying()).handler(stock(left)).handle(place(tea)));
    assertEquals(Optional.of(new Order(tea)), o);
    assertEquals(Optional.empty(), run(Fx.<Order>top().handler(trying()).handler(stock(left)).handle(place(pot))));
  }
}
