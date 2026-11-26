// javac: incompatible types
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;

import java.util.*;

class SmallerOnPurpose { Object x() { return run(Fx.<Shop.Order>top().handler(stock(Map.of()))
  .<Shop.Stock, Shop.Order>handler(first()).handler(clockAt(1)).handle(place(null))); } }
