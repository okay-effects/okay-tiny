package sample;

public class Refused extends RuntimeException {
  private static final long serialVersionUID = 1;
  public Refused(String why) { super(why); }
}
