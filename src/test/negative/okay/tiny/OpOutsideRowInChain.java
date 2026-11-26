// javac: incompatible types
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;

import java.util.*;

class OpOutsideRowInChain { static <R extends Shop.Stock> Comp<Long, R> p() {
  return perform(new Shop.Stock.Reserve("a", 1)).flatMap(ok -> perform(new Shop.Clock.Now())); } }
