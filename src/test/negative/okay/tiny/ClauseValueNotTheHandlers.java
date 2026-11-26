// javac: incompatible types
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;

import java.util.*;

class ClauseValueNotTheHandlers { static <A, R> Handler<Shop.Refusal, A, Optional<A>, R> h() {
  return folding(Shop.Refusal.class, null, (s, a) -> Optional.of(a), op -> switch (op) {
    case Shop.Refusal.Invalid i -> on(i, (s, k) -> pure("none")); }); } }
