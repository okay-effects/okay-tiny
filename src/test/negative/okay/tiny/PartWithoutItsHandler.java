// javac: incompatible types
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;

import java.util.*;

class PartWithoutItsHandler { static <R extends Shop.Stock & Shop.Clock> Comp<Shop.Order, R> p() {
  return Fx.<R, Shop.Order>row().handle(place(null)); } }
