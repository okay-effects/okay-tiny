// javac: no suitable method found for start
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;
import static okay.tiny.Std.*;

class StartWithEffectsKept { static <R extends Std.Async & Shop.Clock> Object x() {
  Comp<Long, R> program = perform(new Clock.Now());
  return start(program); } }
