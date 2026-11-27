// javac: method handler in class
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;

/**
 * A handler that says its clauses ask nothing, and so that the rest they are given asks nothing — a rest it may run
 * by itself — is not put inside a handler of the clock: there the rest still asks the clock.
 */
class RestRunByItself {
  static Handler<Mail, String, String, Object> mailThatRunsItsRest() {
    return handling(Mail.class, op -> switch (op) {
      case Mail.Send send -> on(send, (none, k) -> pure(Fx.run(k.apply(null, null))));
    });
  }

  static <R extends Mail & Clock> Comp<String, R> letter() {
    return Fx.<Void, Mail, R>perform(new Mail.Send("ann")).flatMap(sent -> perform(new Clock.Now()))
        .map(at -> "at " + at);
  }

  Object x() { return run(Fx.<String>top().handler(clockAt(7)).handler(mailThatRunsItsRest()).handle(letter())); }
}
