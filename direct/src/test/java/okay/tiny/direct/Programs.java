package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.ArrayList;
import java.util.List;

/** Programs in direct style: each is compiled by the plugin, and DirectTest runs it. */
final class Programs {
  private Programs() {}

  sealed interface Stock {
    record Reserve(String sku, int qty) implements Stock, Op<Boolean, Stock> {}
    record Price(String sku) implements Stock, Op<Integer, Stock> {}
  }

  sealed interface Refusal {
    record Invalid(String what) implements Refusal, Op<Void, Refusal> {}
  }

  record Line(String sku, int qty) {}

  record Order(List<String> skus, int total) {}

  /** A declaration, a for-each with an if in it, a return of a value. */
  @Direct
  static <R extends Stock & Refusal> Comp<Order, R> place(List<Line> lines) {
    if (lines.isEmpty()) $(perform(new Refusal.Invalid("an empty cart")));
    for (Line line : lines) {
      boolean reserved = $(perform(new Stock.Reserve(line.sku(), line.qty())));
      if (!reserved) $(perform(new Refusal.Invalid(line.sku())));
    }
    List<Integer> prices = $(collect(lines, line -> perform(new Stock.Price(line.sku()))));
    int total = 0;                              // what follows the last $ is plain Java: a variable may change
    for (int i = 0; i < lines.size(); i++) total += prices.get(i) * lines.get(i).qty();
    return pure(new Order(lines.stream().map(Line::sku).toList(), total));
  }

  /** Two $ in one expression, in the order written; a state with no types said. */
  @Direct
  static <R extends State<Integer> & Emit<String>> Comp<Integer, R> counted(String word) {
    $(tell("counting " + word));
    Integer before = $(get());
    $(put(before + word.length()));
    int twice = $(get()) + $(get());
    return pure(twice);
  }

  /** A branch that ends the method, and one that goes on. */
  @Direct
  static <R extends State<Integer> & Emit<String>> Comp<String, R> branches(int n) {
    if (n < 0) {
      $(tell("negative"));
      return pure("refused");
    } else if (n == 0) {
      $(tell("zero"));
    } else {
      $(put(n));
    }
    Integer now = $(get());
    return pure("state " + now);
  }

  /** Calls in the tail, between two methods: no stack, no heap. */
  @Direct
  static <R extends State<Long>> Comp<Boolean, R> even(long n) {
    if (n == 0) return pure(true);
    $(Programs.<R>bump());
    return odd(n - 1);
  }

  @Direct
  static <R extends State<Long>> Comp<Boolean, R> odd(long n) {
    if (n == 0) return pure(false);
    $(Programs.<R>bump());
    return even(n - 1);
  }

  static <R extends State<Long>> Comp<Void, R> bump() { return update((Long count) -> count + 1); }

  /** try-finally is ensuring; a block; a value that is not used. */
  @Direct
  static <R extends Refusal> Comp<String, R> closing(List<String> log, boolean refuse) {
    try {
      log.add("opened");
      {
        if (refuse) $(perform(new Refusal.Invalid("no")));
      }
      log.add("used");
    } finally {
      log.add("closed");
    }
    return pure("done");
  }

  /** Every way: a $ under a handler that goes on with the rest twice. */
  @Direct
  static <R extends Choice> Comp<String, R> pairs() {
    String letter = $(choose(List.of("a", "b")));
    int digit = $(choose(List.of(1, 2)));
    return pure(letter + digit);
  }

  /** Nothing is run when the program is made. */
  @Direct
  static <R> Comp<Integer, R> lazy(List<String> log) {
    log.add("run");
    return pure(1);
  }
}
