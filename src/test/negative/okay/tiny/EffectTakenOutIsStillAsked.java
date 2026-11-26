// javac: incompatible types
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;

import java.util.*;

class EffectTakenOutIsStillAsked { static <R extends Shop.Stock & Shop.Clock> Comp<Void, R> p() {
  return Shop.<R>tried(null).flatMap(o -> perform(new Shop.Refusal.Invalid("a", "b"))); } }
