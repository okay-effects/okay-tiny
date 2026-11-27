package sample;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The same table as an entity: what is changed in one is written when its transaction is committed. */
@Entity
@Table(name = "accounts")
public class Account {
  @Id private String name;
  private long balance;

  protected Account() {}

  public String name() { return name; }

  public long balance() { return balance; }

  public void add(long amount) { balance += amount; }
}
