// javac: String cannot be converted to Integer
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;

import java.util.*;

class WrongNextState { static <B, R> Handler<Shop.Counter, B, B, R> h() {
  return folding(Shop.Counter.class, 0, (n, b) -> b, op -> switch (op) {
    case Shop.Counter.Next next -> on(next, (n, k) -> k.apply(n + 1, "one more")); }); } }
