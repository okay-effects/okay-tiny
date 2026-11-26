// javac: method handler in class
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;

import java.util.*;

class PartValueOfAnotherType { static <R extends Shop.Stock & Shop.Clock> Comp<Shop.Order, R> p() {
  return Fx.<R, Shop.Order>row().handler(leaving("no")).handle(place(null)); } }
