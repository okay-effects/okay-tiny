// javac: incompatible types
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;

import java.util.*;

/** The program asks Clock too, and no handler of it is put. */
class MissingHandler {
  Object x() {
    return run(Fx.<Shop.Order>top().handler(first()).handler(stock(Map.of())).handle(place(null)));
  }
}
