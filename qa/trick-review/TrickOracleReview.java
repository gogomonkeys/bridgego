package com.bridge.room;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Pure-rule oracle: public rule partition and independent mutable card inventory. */
public final class TrickOracleReview {
  static final List<String> SUITS = List.of("CLUB", "DIAMOND", "HEART", "SPADE");
  static final List<String> CONTRACTS = List.of("CLUB", "DIAMOND", "HEART", "SPADE", "NO_TRUMP");
  static final List<GameState.Card> DECK = new ArrayList<>(), AUTO_ORDER = new ArrayList<>();
  static long firstTricks, completeDeals, validPlays, rejectionCases, viewCases;
  static {
    for (String suit : SUITS) for (int rank = 2; rank <= 14; rank++) DECK.add(new GameState.Card(suit, rank));
    // Explicit total order, not the production comparator.
    for (int rank = 2; rank <= 14; rank++) for (String suit : SUITS) AUTO_ORDER.add(card(suit, rank));
  }
  static GameState.Card card(String suit, int rank) { return DECK.get(SUITS.indexOf(suit) * 13 + rank - 2); }
  static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
  static int winner(List<GameState.Card> orderedCards, int leader, String contract) {
    // Rule-level oracle: choose a winning suit first, then rank its eligible cards.
    String winningSuit = orderedCards.get(0).suit();
    if (!contract.equals("NO_TRUMP") && orderedCards.stream().anyMatch(c -> c.suit().equals(contract))) winningSuit = contract;
    List<Integer> eligible = new ArrayList<>();
    for (int i = 0; i < 4; i++) if (orderedCards.get(i).suit().equals(winningSuit)) eligible.add(i);
    eligible.sort((a, b) -> Integer.compare(orderedCards.get(b).rank(), orderedCards.get(a).rank()));
    return (leader + eligible.get(0)) % 4;
  }
  static Map<Integer,List<GameState.Card>> forcedHands(List<GameState.Card> ordered, int leader) {
    Map<Integer,List<GameState.Card>> hands = new HashMap<>();
    for (int s = 0; s < 4; s++) hands.put(s, new ArrayList<>());
    Set<GameState.Card> used = new HashSet<>(ordered);
    require(used.size() == 4, "invalid oracle first trick");
    for (int i = 0; i < 4; i++) hands.get((leader + i) % 4).add(ordered.get(i));
    String led = ordered.get(0).suit();
    // A forced off-suit player must really be void in the led suit.
    for (GameState.Card c : DECK) if (!used.contains(c) && c.suit().equals(led)) {
      boolean assigned = false;
      for (int i = 0; i < 4; i++) {
        List<GameState.Card> hand = hands.get((leader + i) % 4);
        if (ordered.get(i).suit().equals(led) && hand.size() < 13) { hand.add(c); used.add(c); assigned = true; break; }
      }
      require(assigned, "oracle deal could not allocate led suit");
    }
    for (GameState.Card c : DECK) if (!used.contains(c)) for (int s = 0; s < 4; s++) if (hands.get(s).size() < 13) {
      hands.get(s).add(c); used.add(c); break;
    }
    return hands;
  }
  static void firstTrick(List<GameState.Card> cards, String contract) {
    int leader = (int)(firstTricks % 4);
    TrickEngine engine = new TrickEngine(forcedHands(cards, leader), (leader + 3) % 4, contract);
    require(engine.nextSeat() == leader && engine.leaderSeat() == leader, "opening lead left of last bidder");
    for (int i = 0; i < 4; i++) {
      int seat = (leader + i) % 4;
      require(engine.legalCards(seat).contains(cards.get(i)), "oracle generated illegal forced card");
      engine.play(seat, cards.get(i));
    }
    int expected = winner(cards, leader, contract);
    TrickEngine.Trick trick = engine.completedTricks().get(0);
    require(trick.winnerSeat() == expected && engine.nextSeat() == expected && engine.leaderSeat() == expected,
        "trick winner/next leader mismatch: " + contract + " " + cards);
    require(trick.number() == 1 && trick.leaderSeat() == leader && engine.trickNumber() == 2, "trick metadata");
    require(trick.plays().stream().allMatch(p -> p.source().equals("MANUAL")), "manual play source");
    require(engine.tricksByTeam().get(expected % 2 == 0 ? "NS" : "EW") == 1, "opposite-seat team winner");
    firstTricks++;
  }
  static Set<GameState.Card> legal(Set<GameState.Card> hand, List<GameState.Card> trick) {
    if (trick.isEmpty()) return new HashSet<>(hand);
    Set<GameState.Card> sameSuit = new HashSet<>();
    for (GameState.Card c : hand) if (c.suit().equals(trick.get(0).suit())) sameSuit.add(c);
    return sameSuit.isEmpty() ? new HashSet<>(hand) : sameSuit;
  }
  static String snapshot(TrickEngine e) {
    return e.nextSeat()+"|"+e.leaderSeat()+"|"+e.trickNumber()+"|"+e.currentTrick()+"|"+e.completedTricks()
        +"|"+e.hand(0)+e.hand(1)+e.hand(2)+e.hand(3)+"|"+e.tricksByTeam();
  }
  static void rejectsUnchanged(TrickEngine e, Runnable action, boolean finished) {
    String before = snapshot(e);
    try { action.run(); throw new AssertionError("illegal play accepted"); }
    catch (IllegalArgumentException expected) { require(!finished, "wrong complete-deal exception"); }
    catch (IllegalStateException expected) { require(finished, "wrong active-deal exception"); }
    require(before.equals(snapshot(e)), "rejected action mutated engine");
    rejectionCases++;
  }
  static void completeDeal(long seed, int bidder, String contract) {
    List<GameState.Card> shuffled = new ArrayList<>(DECK);
    Random random = new Random(seed);
    Collections.shuffle(shuffled, random);
    Map<Integer,List<GameState.Card>> hands = new HashMap<>();
    Map<Integer,Set<GameState.Card>> expectedHands = new HashMap<>();
    for (int s = 0; s < 4; s++) {
      hands.put(s, new ArrayList<>(shuffled.subList(13*s,13*s+13)));
      expectedHands.put(s, new HashSet<>(hands.get(s)));
    }
    TrickEngine engine = new TrickEngine(hands, bidder, contract);
    // Constructor must own its hand inventory independently of the caller's collections.
    for (List<GameState.Card> hand : hands.values()) hand.clear();
    hands.clear();
    int expectedNext = (bidder+1)%4, leader = expectedNext, completed = 0, ns = 0;
    List<GameState.Card> trick = new ArrayList<>();
    Set<GameState.Card> played = new HashSet<>();
    for (int n = 0; n < 52; n++) {
      require(engine.nextSeat() == expectedNext, "turn mismatch");
      Set<GameState.Card> legal = legal(expectedHands.get(expectedNext), trick);
      require(new HashSet<>(engine.legalCards(expectedNext)).equals(legal), "follow-suit legal filtering mismatch");
      for (int s = 0; s < 4; s++) {
        require(new HashSet<>(engine.hand(s)).equals(expectedHands.get(s)), "hand inventory mismatch");
        if (s != expectedNext) require(engine.legalCards(s).isEmpty(), "out-of-turn legal cards exposed");
      }
      final int actor = expectedNext;
      GameState.Card any = legal.iterator().next();
      rejectsUnchanged(engine, () -> engine.play((actor+1)%4, any), false);
      if (!trick.isEmpty()) for (GameState.Card illegal : expectedHands.get(actor)) if (!legal.contains(illegal)) {
        rejectsUnchanged(engine, () -> engine.play(actor, illegal), false); break;
      }
      GameState.Card chosen;
      boolean automatic = n%3 != 0;
      if (automatic) {
        chosen = AUTO_ORDER.stream().filter(legal::contains).findFirst().orElseThrow();
        engine.timeout();
      } else {
        List<GameState.Card> options = new ArrayList<>(legal);options.sort(Comparator.comparing(GameState.Card::suit).thenComparingInt(GameState.Card::rank));
        chosen = options.get(random.nextInt(options.size()));engine.play(actor, chosen);
      }
      expectedHands.get(actor).remove(chosen);require(played.add(chosen), "duplicate played card");trick.add(chosen);
      TrickEngine.Play actual = trick.size()==4 ? engine.completedTricks().get(completed).plays().get(3)
          : engine.currentTrick().get(trick.size()-1);
      require(actual.seat()==actor && actual.card().equals(chosen) && actual.source().equals(automatic?"TIMEOUT":"MANUAL"), "actual card/source differs from independent selection");
      if (trick.size()==4) {
        int expectedWinner=winner(trick,leader,contract);
        require(engine.completedTricks().get(completed).winnerSeat()==expectedWinner, "complete-deal trick winner mismatch");
        if(expectedWinner%2==0)ns++;
        completed++;expectedNext=leader=expectedWinner;trick.clear();
      } else expectedNext=(actor+1)%4;
      require(engine.completedTricks().size()==completed && engine.currentTrick().size()==trick.size(), "history count mismatch");
      require(engine.tricksByTeam().get("NS")==ns && engine.tricksByTeam().get("EW")==completed-ns, "team trick conservation");
      int remaining=expectedHands.values().stream().mapToInt(Set::size).sum();
      require(remaining+4*completed+trick.size()==52, "52-card conservation");validPlays++;
    }
    require(engine.finished() && engine.nextSeat()==null && engine.completedTricks().size()==13 && played.size()==52, "terminal deal invariant");
    require(engine.completedTricks().stream().flatMap(t->t.plays().stream()).map(TrickEngine.Play::card).distinct().count()==52, "history card uniqueness");
    for(int s=0;s<4;s++)require(engine.hand(s).isEmpty()&&engine.legalCards(s).isEmpty(),"terminal hands/legal choices");
    rejectsUnchanged(engine,engine::timeout,true);
    rejectsUnchanged(engine,()->engine.play(0,DECK.get(0)),true);
    completeDeals++;
  }
  static void rejectionAndViewCases() {
    var forced=List.of(card("HEART",14),card("HEART",3),card("HEART",4),card("HEART",5));
    TrickEngine e=new TrickEngine(forcedHands(forced,0),3,"NO_TRUMP");
    rejectsUnchanged(e,()->e.play(0,null),false);
    rejectsUnchanged(e,()->e.play(-1,forced.get(0)),false);
    rejectsUnchanged(e,()->e.play(4,forced.get(0)),false);
    rejectsUnchanged(e,()->e.play(0,e.hand(1).get(0)),false);
    e.play(0,forced.get(0));rejectsUnchanged(e,()->e.play(0,forced.get(0)),false);
    for(int seat=1;seat<4;seat++)e.play(seat,forced.get(seat));
    require(e.nextSeat()==0,"replay setup winner");
    rejectsUnchanged(e,()->e.play(0,forced.get(0)),false);
    for(Runnable mutate:List.<Runnable>of(()->e.hand(0).clear(),()->e.currentTrick().clear(),()->e.completedTricks().clear(),()->e.completedTricks().get(0).plays().clear(),()->e.tricksByTeam().clear())) {
      String before=snapshot(e);try{mutate.run();throw new AssertionError("mutable engine view");}catch(UnsupportedOperationException expected){}
      require(before.equals(snapshot(e)),"view mutation leaked");viewCases++;
    }
  }
  public static void main(String[] args)throws Exception {
    List<GameState.Card> representative=DECK.stream().filter(c->Set.of(2,10,13,14).contains(c.rank())).toList();
    for(var a:representative)for(var b:representative)if(!b.equals(a))for(var c:representative)if(!c.equals(a)&&!c.equals(b))for(var d:representative)if(!d.equals(a)&&!d.equals(b)&&!d.equals(c))
      for(String contract:CONTRACTS)firstTrick(List.of(a,b,c,d),contract);
    long representativeCases=firstTricks;
    for(String suit:SUITS)for(int a=2;a<=14;a++)for(int b=2;b<=14;b++)if(b!=a)for(int c=2;c<=14;c++)if(c!=a&&c!=b)for(int d=2;d<=14;d++)if(d!=a&&d!=b&&d!=c)
      for(String contract:CONTRACTS)firstTrick(List.of(card(suit,a),card(suit,b),card(suit,c),card(suit,d)),contract);
    long fullRankCases=firstTricks-representativeCases;
    for(int seed=0;seed<15;seed++)for(int bidder=0;bidder<4;bidder++)for(String contract:CONTRACTS)completeDeal(0xB71D6EL+seed,bidder,contract);
    rejectionAndViewCases();
    String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(args[0]))));
    String json="""
      {"status":"PASS","representative_suit_rank_first_tricks":%d,"full_rank_same_suit_first_tricks":%d,"complete_seeded_deals":%d,"complete_deal_valid_plays":%d,"rejected_actions_unchanged":%d,"immutable_view_cases":%d,"production_source_sha256":"%s","java_runtime":"%s"}
      """.formatted(representativeCases,fullRankCases,completeDeals,validPlays,rejectionCases,viewCases,hash,System.getProperty("java.version"));
    require(representativeCases==218400&&fullRankCases==343200&&completeDeals==300&&validPlays==15600,"enumeration counts");
    Files.writeString(Path.of(args[1]),json);System.out.print(json);
  }
}
