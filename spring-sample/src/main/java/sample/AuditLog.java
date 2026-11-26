package sample;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import okay.tiny.Fx.Op;
import org.springframework.stereotype.Component;

/** Every operation asked by a use case, as data: given to run as its second argument. */
@Component
public class AuditLog {
  private final List<Op<?, ?>> asked = new CopyOnWriteArrayList<>();

  public void record(Op<?, ?> op) { asked.add(op); }

  public List<Op<?, ?>> asked() { return List.copyOf(asked); }

  public void clear() { asked.clear(); }
}
