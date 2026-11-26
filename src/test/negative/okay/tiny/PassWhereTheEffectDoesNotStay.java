// javac: incompatible types
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;

import java.util.*;

class PassWhereTheEffectDoesNotStay { static <B> Handler<Shop.Stock, B, B, Object> h() {
  return handling(Shop.Stock.class, op -> switch (op) {
    case Shop.Stock.Reserve r -> on(r, (s, k) -> perform(r).flatMap(x -> k.apply(x, s))); }); } }
