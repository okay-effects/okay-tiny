// javac: method folding in class
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;

import java.util.*;

class ReturnOfAnotherState { static <R> Handler<Shop.Counter, String, String, R> h() {
  return folding(Shop.Counter.class, 0, (String n, String b) -> b + n, op -> switch (op) {
    case Shop.Counter.Next next -> on(next, (n, k) -> k.apply(1, n)); }); } }
