// javac: method handler in class
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;

import java.util.*;

class HandlerOfAnotherPartValue { static <R extends Shop.Stock & Shop.Clock> Comp<Shop.Checked, R> p() {
  return Fx.<R, String>row().handler(collecting()).handle(pure("not an order")); } }
