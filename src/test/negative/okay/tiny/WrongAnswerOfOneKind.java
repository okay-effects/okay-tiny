// javac: method answer in class
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;

import java.util.*;

class WrongAnswerOfOneKind { static <B, R> Handler<Shop.Prices, B, B, R> h() {
  return handling(Shop.Prices.class, op -> switch (op) {
    case Shop.Prices.Price p -> answer(p, 1L); case Shop.Prices.InStock i -> answer(i, 1L); }); } }
