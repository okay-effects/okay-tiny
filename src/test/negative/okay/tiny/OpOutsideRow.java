// javac: incompatible types
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;

import java.util.*;

class OpOutsideRow { static <R extends Shop.Stock> Comp<Long, R> p() { return perform(new Shop.Clock.Now()); } }
