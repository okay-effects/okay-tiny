// javac: the switch expression does not cover all possible input values
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;

import java.util.*;

class MissingCase { static <B, R> Handler<Shop.Prices, B, B, R> h() {
  return handling(Shop.Prices.class, op -> switch (op) { case Shop.Prices.Price p -> answer(p, 1L); }); } }
