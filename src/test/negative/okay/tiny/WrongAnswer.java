// javac: String cannot be converted to Long
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;

import java.util.*;

class WrongAnswer { static <B, R> Handler<Shop.Clock, B, B, R> h() {
  return handling(Shop.Clock.class, op -> switch (op) {
    case Shop.Clock.Now now -> on(now, (s, k) -> k.apply("noon", s)); }); } }
