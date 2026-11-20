select tce1_0.id,tce1_0.name from tagged_cards tce1_0 order by 1
select tce1_0.id,l1_0.label_key,l1_0.label_val from tagged_cards tce1_0 join tagged_card_labels l1_0 on tce1_0.id=l1_0.card_id where tce1_0.id in (?,?,?,?,?) order by 1
select tce1_0.id,c1_0.status_key,c1_0.count_val from tagged_cards tce1_0 join tagged_card_counts c1_0 on tce1_0.id=c1_0.card_id where tce1_0.id in (?,?,?,?,?) order by 1
select tce1_0.id,sm1_0.spot_key,sm1_0.city,sm1_0.lat,sm1_0.state from tagged_cards tce1_0 join tagged_card_spots_map sm1_0 on tce1_0.id=sm1_0.card_id where tce1_0.id in (?,?,?,?,?) order by 1
select tce1_0.id,l1_0.label_key,l1_0.label_val from tagged_cards tce1_0 join tagged_cards h1_0 on h1_0.id=tce1_0.holder_id join tagged_card_labels l1_0 on h1_0.id=l1_0.card_id where tce1_0.id in (?,?,?,?,?) order by 1
