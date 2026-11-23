select tce1_0.id,tce1_0.name from tagged_cards tce1_0 order by 1
select tce1_0.id,t1_0.tag from tagged_cards tce1_0 join tagged_card_tags t1_0 on tce1_0.id=t1_0.card_id where tce1_0.id in (?,?,?,?,?) order by 1,2 asc nulls last
select tce1_0.id,t1_0.tag from tagged_cards tce1_0 join tagged_card_tags t1_0 on tce1_0.id=t1_0.card_id where tce1_0.id in (?,?,?,?,?) order by 1,2 asc nulls last
select tce1_0.id,s1_0.state from tagged_cards tce1_0 join tagged_card_states s1_0 on tce1_0.id=s1_0.card_id where tce1_0.id in (?,?,?,?,?) order by 1,2 asc nulls last
select tce1_0.id,s1_0.score from tagged_cards tce1_0 join tagged_card_scores s1_0 on tce1_0.id=s1_0.card_id where tce1_0.id in (?,?,?,?,?) order by 1,s1_0.pos,2 asc nulls last
select tce1_0.id,t1_0.tag from tagged_cards tce1_0 join tagged_cards h1_0 on h1_0.id=tce1_0.holder_id join tagged_card_tags t1_0 on h1_0.id=t1_0.card_id where tce1_0.id in (?,?,?,?,?) order by 1,2 asc nulls last
