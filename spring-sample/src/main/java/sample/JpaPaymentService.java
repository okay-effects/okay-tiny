package sample;

import static okay.tiny.Fx.run;

import okay.tiny.Fx;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sample.Payments.Receipt;

/** The same use cases with the accounts answered through JPA: one handler in the chain is another. */
@Service
public class JpaPaymentService {
  private final Handlers handlers;
  private final JpaHandlers jpa;

  public JpaPaymentService(Handlers handlers, JpaHandlers jpa) {
    this.handlers = handlers;
    this.jpa = jpa;
  }

  @Transactional
  public Receipt pay(String from, String to, long amount) {
    return run(Fx.<Receipt>top().handler(handlers.refusing()).handler(handlers.clock()).handler(jpa.accounts())
        .handle(Payments.pay(from, to, amount)));
  }

  /** Each payment in a transaction of its own, and so in a persistence context of its own. */
  public String payBoth(String from, String to, long first, long second) {
    return run(Fx.<String>top().handler(handlers.clock()).handler(jpa.accounts())
        .handle(PaymentService.both(handlers, from, to, first, second)));
  }
}
