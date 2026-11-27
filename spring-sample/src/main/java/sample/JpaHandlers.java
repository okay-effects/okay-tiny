package sample;

import static okay.tiny.Fx.*;

import okay.tiny.Fx.Handler;
import org.springframework.stereotype.Component;
import sample.Payments.Accounts;

/**
 * The accounts again, through JPA: the same effect, another handler, and the programs do not change. A move is two
 * entities changed and nothing said to save them: the persistence context of the transaction in force writes them
 * when it is committed. That context is the thread's, as the transaction is, and the machine runs a program on the
 * thread that called run: so every operation of a use case is answered in one context.
 */
@Component
public class JpaHandlers {
  private final AccountRepository accounts;

  public JpaHandlers(AccountRepository accounts) { this.accounts = accounts; }

  public <B, R> Handler<Accounts, B, B, R> accounts() {
    return handling(Accounts.class, op -> switch (op) {
      case Accounts.Balance b -> answer(b, account(b.account()).balance());
      case Accounts.Move m -> {
        account(m.from()).add(-m.amount());
        account(m.to()).add(m.amount());
        yield answer(m, null);
      }
    });
  }

  private Account account(String name) {
    return accounts.findById(name).orElseThrow(() -> new Refused("no account " + name));
  }
}
