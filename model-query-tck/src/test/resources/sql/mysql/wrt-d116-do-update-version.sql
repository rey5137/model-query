insert into ins_assigned(id,code,name,version) values (2,'c2','X2',?) as excluded(id,code,name,version) on duplicate key update name=excluded.name,version=(ins_assigned.version+1)
