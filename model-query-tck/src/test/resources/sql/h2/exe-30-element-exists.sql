select tce1_0.id,tce1_0.name from tagged_cards tce1_0 where exists(select 1 from tagged_card_tags t1_0 where t1_0.tag=? and tce1_0.id=t1_0.card_id) order by 1
select tce1_0.id,tce1_0.name from tagged_cards tce1_0 where exists(select 1 from tagged_card_tags t1_0 where tce1_0.id=t1_0.card_id) order by 1
select tce1_0.id,tce1_0.name from tagged_cards tce1_0 where not exists(select 1 from tagged_card_tags t1_0 where t1_0.tag is not null and tce1_0.id=t1_0.card_id) order by 1
select tce1_0.id,tce1_0.name from tagged_cards tce1_0 where exists(select 1 from tagged_card_spots s1_0 where s1_0.city=? and tce1_0.id=s1_0.card_id) order by 1
