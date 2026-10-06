insert into ins_assigned(id,code,name,version) values (2,'c2','X2',?), (3,'c3','N3',?) as excluded(id,code,name,version) on duplicate key update id=ins_assigned.id
