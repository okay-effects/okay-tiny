// javac: no suitable method found for run
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;

import java.util.*;

class RunWithEffectsKept { static <R extends Shop.Clock> Object x() {
  return run(Fx.<R, Shop.Order>row().handler(stock(Map.of())).handler(first()).handle(place(null))); } }
