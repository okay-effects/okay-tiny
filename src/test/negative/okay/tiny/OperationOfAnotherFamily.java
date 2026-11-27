// javac: inference variable F has incompatible equality constraints Clock,Stock
package okay.tiny;

import static okay.tiny.Fx.*;

class OperationOfAnotherFamily {
  sealed interface Stock { record Reserve(String sku) implements Stock, Op<Boolean, Stock> {} }
  sealed interface Clock {
    record Now() implements Clock, Op<Long, Clock> {}
    record Odd() implements Clock, Op<Long, Stock> {}      // a slip: it says another effect than the one it is of
  }
  static <B, R> Handler<Clock, B, B, R> clock() {
    return handling(Clock.class, op -> switch (op) {
      case Clock.Now now -> answer(now, 1L);
      case Clock.Odd odd -> answer(odd, 2L);
    });
  } }
