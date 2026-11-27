package okay.tiny;

import static okay.tiny.Fx.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A real flow on the kernel: the taking of a booking of okay-shop, seven effects (okay-java's BookingSets). */
final class Booking {
  // ---- the model, cut down
  record Offer(String key, int minutes, long price, boolean hidden) {}
  record Address(String street, String postcode, String city) {}
  record Geo(double lat, double lon) {}
  record Area(List<String> cities, int radiusKm, Geo centre, long fee) {}
  record Conf(Map<String, Offer> offers, Optional<Area> area, String refPrefix) {}
  record Offered(Offer offer, boolean atClient, Optional<Address> address, long price) {}
  record Booked(String ref, String key, String service, long start, String name, String contact, long consentAt,
                Optional<Address> address, long price) {}
  record Response(int status, String body) {}
  record Or<A>(String refused, A value) {
    static <A> Or<A> of(A value) { return new Or<>(null, value); }
    static <A> Or<A> refused(String why) { return new Or<>(why, null); }
    boolean ok() { return refused == null; }
  }

  // ---- the effects
  sealed interface Store {
    record Allow(String key, int max, long window, long now) implements Store, Op<Boolean, Store> {}
    record Book(Booked booking, long now) implements Store, Op<Or<Booked>, Store> {}
  }
  sealed interface Mail {
    record ToClient(String to, String text) implements Mail, Op<Boolean, Mail> {}
    record ToOwner(String text) implements Mail, Op<Boolean, Mail> {}
  }
  sealed interface Captcha {
    record Verify(String token, String ip) implements Captcha, Op<Boolean, Captcha> {}
  }
  sealed interface Ids {
    record Ref(String prefix) implements Ids, Op<String, Ids> {}
    record Secret() implements Ids, Op<String, Ids> {}
  }
  sealed interface Clock {
    record Now() implements Clock, Op<Long, Clock> {}
  }
  sealed interface Notify {
    record Tell(String notice) implements Notify, Op<Void, Notify> {}
  }
  sealed interface Geocoder {
    record Find(Address address) implements Geocoder, Op<Optional<Geo>, Geocoder> {}
  }
  /** A refusal: the flow is left with it. Its answer never comes. */
  sealed interface Refuse {
    record Because(String why, int status) implements Refuse, Op<Void, Refuse> {}
  }

  static final long TEN_MINUTES = 600_000L;

  static Or<Offered> offered(Conf c, String service, String mode) {
    Offer o = c.offers().get(service);
    if (o == null || o.hidden()) return Or.refused("service");
    boolean atClient = mode.equals("client");
    if (atClient && c.area().isEmpty()) return Or.refused("mode");
    return Or.of(new Offered(o, atClient, Optional.empty(), o.price() + (atClient ? c.area().get().fee() : 0)));
  }

  static double km(Geo a, Geo b) { return Math.hypot(a.lat() - b.lat(), a.lon() - b.lon()) * 111; }

  // ---- the programs

  static <T, R extends Refuse> Comp<T, R> refuse(String why, int status) {
    return perform(new Refuse.Because(why, status)).map(never -> null);
  }

  static <R extends Refuse> Comp<Void, R> require(boolean holds, String why, int status) {
    return holds ? pure(null) : refuse(why, status);
  }

  static <R extends Geocoder & Refuse> Comp<Boolean, R> inside(Conf c, Address a) {
    if (c.area().isEmpty()) return pure(false);
    Area area = c.area().get();
    if (area.cities().contains(a.city())) return pure(true);
    if (area.radiusKm() <= 0) return pure(false);
    return perform(new Geocoder.Find(a)).flatMap(found ->
        found.isEmpty() ? refuse("area-unchecked", 409) : pure(km(area.centre(), found.get()) <= area.radiusKm()));
  }

  static <R extends Geocoder & Refuse> Comp<Offered, R> addressed(Conf c, Offered p, Map<String, String> f) {
    if (!p.atClient()) return pure(p);
    Address a = new Address(f.getOrDefault("street", "").trim(), f.getOrDefault("postcode", "").trim(),
        f.getOrDefault("city", "").trim());
    boolean written = !a.street().isEmpty() && !a.city().isEmpty() && a.postcode().matches("[0-9]{2}-[0-9]{3}");
    return require(written, "address", 400).flatMap(x -> inside(c, a)).flatMap(in -> require(in, "area", 409))
        .map(x -> new Offered(p.offer(), true, Optional.of(a), p.price()));
  }

  static <R extends Ids & Store & Refuse> Comp<Booked, R> reserve(Conf c, Offered p, long at, String name,
                                                                  String contact, long now) {
    return perform(new Ids.Ref(c.refPrefix() + "B")).flatMap(ref ->
        perform(new Ids.Secret()).flatMap(key ->
            perform(new Store.Book(new Booked(ref, key, p.offer().key(), at, name, contact, now, p.address(),
                p.price()), now)).flatMap(made -> made.ok() ? pure(made.value()) : refuse(made.refused(), 409))));
  }

  static <R extends Mail & Notify> Comp<Void, R> told(Booked b) {
    return perform(new Mail.ToClient(b.contact(), "booked " + b.ref()))
        .flatMap(x -> perform(new Mail.ToOwner("booked " + b.ref() + " by " + b.name())))
        .flatMap(x -> perform(new Notify.Tell("booked " + b.ref())));
  }

  /** The flow: every check a line; a refusal leaves it — the handler of Refuse is around it, in the flow itself. */
  static <R extends Store & Mail & Captcha & Ids & Clock & Notify & Geocoder> Comp<Response, R> book(
      Conf c, Map<String, String> f, String ip) {
    return Fx.<R, Response>row()
        .handler(handling(Refuse.class, op -> switch (op) {
          case Refuse.Because b -> on(b, (s, k) -> pure(new Response(b.status(), "refused: " + b.why())));
        }))
        .handle(flow(c, f, ip));
  }

  static <R extends Store & Mail & Captcha & Ids & Clock & Notify & Geocoder & Refuse> Comp<Response, R> flow(
      Conf c, Map<String, String> f, String ip) {
    String name = f.getOrDefault("name", "").trim();
    String contact = f.getOrDefault("contact", "").trim();
    Or<Offered> o = offered(c, f.getOrDefault("service", ""), f.getOrDefault("mode", ""));
    return require(!name.isEmpty(), "name", 400)
        .flatMap(x -> require(contact.contains("@"), "contact", 400))
        .flatMap(x -> require("true".equals(f.get("rodo_consent")), "consent", 400))
        .flatMap(x -> require(o.ok(), o.refused(), 400))
        .flatMap(x -> addressed(c, o.value(), f))
        .flatMap(p -> require(f.containsKey("start"), "start", 400)
            .flatMap(x -> perform(new Clock.Now()))
            .flatMap(now -> perform(new Store.Allow("book:" + ip, 5, TEN_MINUTES, now))
                .flatMap(allowed -> require(allowed, "limit", 429))
                .flatMap(x -> perform(new Captcha.Verify(f.getOrDefault("token", ""), ip)))
                .flatMap(human -> require(human, "captcha", 403))
                .flatMap(x -> reserve(c, p, Long.parseLong(f.get("start")), name, contact, now))))
        .flatMap(made -> told(made).map(done -> new Response(200, "booked " + made.ref())));
  }

  // ---- the world of a test, and the handlers: one function each

  static final class World {
    long now = 1_000_000L;
    boolean human = true;
    int ids;
    final Map<String, Integer> asked = new HashMap<>();
    final Set<Long> taken = new HashSet<>();
    final List<Booked> bookings = new ArrayList<>();
    final List<String> letters = new ArrayList<>();
    final List<String> notices = new ArrayList<>();
    final Map<String, Geo> places = new HashMap<>();
    final List<String> log = new ArrayList<>();
  }

  static Response book(World w, Conf c, Map<String, String> form) {
    return run(Fx.<Response>top()
        .handler(handling(Geocoder.class, op -> switch (op) {
          case Geocoder.Find g -> {
            w.log.add("geocoder");
            yield answer(g, Optional.ofNullable(w.places.get(g.address().city())));
          }
        }))
        .handler(handling(Notify.class, op -> switch (op) {
          case Notify.Tell t -> { w.notices.add(t.notice()); yield answer(t, null); }
        }))
        .handler(handling(Clock.class, op -> switch (op) { case Clock.Now n -> answer(n, w.now); }))
        .handler(handling(Ids.class, op -> switch (op) {
          case Ids.Ref r -> answer(r, r.prefix() + (++w.ids));
          case Ids.Secret s -> answer(s, "key" + (++w.ids));
        }))
        .handler(handling(Captcha.class, op -> switch (op) {
          case Captcha.Verify v -> { w.log.add("captcha"); yield answer(v, w.human); }
        }))
        .handler(handling(Mail.class, op -> switch (op) {
          case Mail.ToClient m -> answer(m, w.letters.add(m.to() + ": " + m.text()));
          case Mail.ToOwner m -> answer(m, w.letters.add("owner: " + m.text()));
        }))
        .handler(handling(Store.class, op -> switch (op) {
          case Store.Allow a -> {
            w.log.add("store");
            yield answer(a, w.asked.merge(a.key(), 1, Integer::sum) <= a.max());
          }
          case Store.Book b -> {
            w.log.add("store");
            if (!w.taken.add(b.booking().start())) yield answer(b, Or.refused("taken"));
            w.bookings.add(b.booking());
            yield answer(b, Or.of(b.booking()));
          }
        }))
        .handle(book(c, form, "10.0.0.1")));
  }

  // ---- the scenarios of okay-java's BookingTest

  static final Conf SHOP = new Conf(
      Map.of("cut", new Offer("cut", 30, 80, false), "old", new Offer("old", 30, 50, true)),
      Optional.of(new Area(List.of("Gdynia"), 15, new Geo(54.5, 18.5), 20)), "S");

  static Map<String, String> form(String... more) {
    var f = new HashMap<>(Map.of("name", "Ann", "contact", "ann@mail", "rodo_consent", "true", "service", "cut",
        "mode", "shop", "start", "5000", "token", "t"));
    for (int i = 0; i < more.length; i += 2) f.put(more[i], more[i + 1]);
    return f;
  }

}
