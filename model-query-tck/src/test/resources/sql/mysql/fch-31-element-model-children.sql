select tce1_0.id from tagged_cards tce1_0 order by 1
select s1_0.city,s1_0.lat,s1_0.state,tce1_0.id from tagged_cards tce1_0 join tagged_card_spots s1_0 on tce1_0.id=s1_0.card_id where tce1_0.id in (?,?,?,?,?)
select os1_0.city,os1_0.lat,os1_0.state,tce1_0.id from tagged_cards tce1_0 join tagged_card_ordered_spots os1_0 on tce1_0.id=os1_0.card_id where tce1_0.id in (?,?,?,?,?) order by os1_0.pos,1 desc
select s1_0.city,s1_0.lat,s1_0.state,tce1_0.id from tagged_cards tce1_0 join tagged_cards h1_0 on h1_0.id=tce1_0.holder_id join tagged_card_spots s1_0 on h1_0.id=s1_0.card_id where tce1_0.id in (?,?,?,?,?)
