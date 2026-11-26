// javac: method handler in class
package okay.tiny;

import static okay.tiny.Fx.*;
import static okay.tiny.Shop.*;

import java.util.*;

/** The handler of Mail asks Clock, and at the top nothing stays for it to ask. */
class HandlerAsksWhatDoesNotStay {
  Object x() {
    return run(Fx.<Long>top().handler(stamped(null)).handle(twoLetters()));
  }
}
