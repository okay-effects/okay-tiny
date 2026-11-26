// javac: incompatible types
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;

import java.util.*;

class ClauseOfAnotherEffect { static <B, R> Handler<Shop.Clock, B, B, R> h() {
  return handling(Shop.Clock.class, op -> switch (op) {
    case Shop.Clock.Now now -> answer(new Shop.Stock.Reserve("a", 1), true); }); } }
