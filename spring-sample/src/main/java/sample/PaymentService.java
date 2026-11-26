package sample;

import static okay.tiny.Fx.run;

import java.util.Optional;
import okay.tiny.Fx;
import okay.tiny.Fx.Comp;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sample.Payments.Accounts;
import sample.Payments.Clock;
import sample.Payments.Receipt;

/** The entry points. Callers, the container and the controller see an ordinary service. */
@Service
public class PaymentService {
  private final Handlers handlers;
  private final AuditLog audit;

  public PaymentService(Handlers handlers, AuditLog audit) {
    this.handlers = handlers;
    this.audit = audit;
  }

  /** The whole use case in the method's transaction: a refusal is an exception, and Spring rolls back. */
  @Transactional
  public Receipt pay(String from, String to, long amount) {
    return run(Fx.<Receipt>top().handler(handlers.accounts()).handler(handlers.clock()).handler(handlers.refusing())
        .handle(Payments.pay(from, to, amount)), audit::record);
  }

  @Transactional
  public Receipt payToClosed(String from, String to, long amount) {
    return run(Fx.<Receipt>top().handler(handlers.accounts()).handler(handlers.clock()).handler(handlers.refusing())
        .handle(Payments.payToClosed(from, to, amount)));
  }

  /**
   * Two payments, each in a transaction of its own, and no exception: a payment that is refused gives none and its
   * transaction is rolled back; the other stays. No @Transactional here.
   */
  public String payBoth(String from, String to, long first, long second) {
    return run(Fx.<String>top().handler(handlers.accounts()).handler(handlers.clock())
        .handle(both(from, to, first, second)));
  }

  private <R extends Accounts & Clock> Comp<String, R> both(String from, String to, long first, long second) {
    Comp<Optional<Receipt>, R> one =
        Fx.<R, Receipt>row().handler(handlers.optional()).handle(handlers.transactional(Payments.pay(from, to, first)));
    Comp<Optional<Receipt>, R> other = Fx.<R, Receipt>row().handler(handlers.optional())
        .handle(handlers.transactional(Payments.payToClosed(from, to, second)));
    return one.flatMap(a -> other.map(b -> said(a) + ", " + said(b)));
  }

  private static String said(Optional<Receipt> receipt) {
    return receipt.isPresent() ? "paid" : "refused";
  }
}
