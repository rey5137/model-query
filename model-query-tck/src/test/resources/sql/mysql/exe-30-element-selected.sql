select tce1_0.id,t1_0.tag from tagged_cards tce1_0 join tagged_card_tags t1_0 on tce1_0.id=t1_0.card_id where t1_0.tag in (?,?) order by 1
select count(*) from tagged_cards tce1_0 join tagged_card_tags t1_0 on tce1_0.id=t1_0.card_id where t1_0.tag in (?,?)
