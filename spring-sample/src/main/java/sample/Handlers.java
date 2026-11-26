package sample;

import static okay.tiny.Fx.*;

import java.util.Optional;
import okay.tiny.Fx;
import okay.tiny.Fx.Comp;
import okay.tiny.Fx.Handler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import sample.Payments.Accounts;
import sample.Payments.Clock;
import sample.Payments.Refuse;

/** The handlers: where the database, the clock and the transaction manager are reached. A bean, like any adapter. */
@Component
public class Handlers {
  private final JdbcTemplate jdbc;
  private final PlatformTransactionManager transactions;

  public Handlers(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  public <B, R> Handler<Accounts, B, B, R> accounts() {
    return handling(Accounts.class, op -> switch (op) {
      case Accounts.Balance b ->
          answer(b, jdbc.queryForObject("select balance from accounts where name = ?", Long.class, b.account()));
      case Accounts.Move m -> {
        jdbc.update("update accounts set balance = balance - ? where name = ?", m.amount(), m.from());
        jdbc.update("update accounts set balance = balance + ? where name = ?", m.amount(), m.to());
        yield answer(m, null);
      }
    });
  }

  public <B, R> Handler<Clock, B, B, R> clock() {
    return handling(Clock.class, op -> switch (op) { case Clock.Now now -> answer(now, System.currentTimeMillis()); });
  }

  /** A refusal as the exception the rest of the service already knows. */
  public <B, R> Handler<Refuse, B, B, R> refusing() {
    return handling(Refuse.class, op -> { switch (op) { case Refuse.Because b -> throw new Refused(b.why()); } });
  }

  /** A refusal as a value: the part is left, and gives none. */
  public <A, R> Handler<Refuse, A, Optional<A>, R> optional() {
    return folding(Refuse.class, null, (s, a) -> Optional.of(a), op -> leave(Optional.empty()));
  }

  /**
   * A transaction around a part of a program: begun when the part is reached, committed when it ends with a value,
   * rolled back when it is left or fails. Spring's transaction is the thread's, and the machine runs a program on
   * the thread that called run: so the part's statements are in it.
   */
  public <A, R> Comp<A, R> transactional(Comp<A, R> part) {
    return Fx.<Object, R>pure(null).flatMap(reached -> {
      TransactionStatus status = transactions.getTransaction(new DefaultTransactionDefinition());
      boolean[] committed = {false};
      return ensuring(part.map(a -> { transactions.commit(status); committed[0] = true; return a; }),
          () -> { if (!committed[0]) transactions.rollback(status); });
    });
  }
}
