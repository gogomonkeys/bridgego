"""Independent expectations for real v0.3 network tests, not production imports."""
SUITS=('CLUB','DIAMOND','HEART','SPADE')
def card_key(card):return (card['rank'],SUITS.index(card['suit']))
def legal_cards(hand,trick):
    if not trick:return sorted(hand,key=card_key)
    led=trick[0]['card']['suit'];following=[card for card in hand if card['suit']==led]
    return sorted(following or hand,key=card_key)
def winner(plays,strain):
    assert len(plays)==4 and len({p['seat'] for p in plays})==4
    led=plays[0]['card']['suit']
    trump=[p for p in plays if strain!='NO_TRUMP' and p['card']['suit']==strain]
    eligible=trump or [p for p in plays if p['card']['suit']==led]
    return max(eligible,key=lambda p:p['card']['rank'])['seat']
def score(level,attack_tricks,attack_privilege,defence_privilege):
    """User's additive custom score, intentionally independent of Java calculator."""
    assert 1<=level<=7 and 0<=attack_tricks<=13
    made=attack_tricks>=6+level
    def team(won,privilege,base=0,bonus=0,penalty=0):
        adj=(1 if won else -1) if privilege else 0
        return dict(won=won,privilegeUsed=privilege,base=base,exactTargetBonus=bonus,penalty=penalty,privilegeAdjustment=adj,total=base+bonus+penalty+adj)
    return {'attack':team(made,attack_privilege,level if made else 0,2 if attack_tricks==6+level else 0,0 if made else attack_tricks-(6+level)),'defence':team(not made,defence_privilege,0 if made else 1)}
