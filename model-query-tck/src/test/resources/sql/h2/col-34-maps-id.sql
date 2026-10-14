update ins_maps_id imie1_0 set note=? where imie1_0.id=?
select imie1_0.id,p1_0.id from ins_maps_id imie1_0 left join ins_assigned p1_0 on p1_0.id=imie1_0.id where p1_0.id=?
