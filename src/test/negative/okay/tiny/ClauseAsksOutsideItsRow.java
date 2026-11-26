// javac: incompatible types
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;

import java.util.*;

class ClauseAsksOutsideItsRow { static <B> Handler<Shop.Mail, B, B, Object> h() {
  return handling(Shop.Mail.class, op -> switch (op) {
    case Shop.Mail.Send m -> on(m, (s, k) -> perform(new Shop.Clock.Now()).flatMap(at -> k.apply(null, s))); }); } }
