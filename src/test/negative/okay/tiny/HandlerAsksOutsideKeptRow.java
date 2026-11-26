// javac: method handler in class
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;

import java.util.*;

class HandlerAsksOutsideKeptRow { static <R extends Shop.Stock> Comp<Long, R> p() {
  return Fx.<R, Long>row().handler(stamped(null)).handle(twoLetters()); } }
