// javac: incompatible types
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;

import java.util.*;

class PartAsksMoreThanKept { static <R extends Shop.Stock> Comp<Optional<Shop.Order>, R> p() {
  return Fx.<R, Shop.Order>row().handler(trying()).handle(place(null)); } }
