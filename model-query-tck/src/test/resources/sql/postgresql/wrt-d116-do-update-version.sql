insert into ins_assigned as iae1_0(id,code,name,version) values (2,'c2','X2',?) on conflict(id) do update set name=excluded.name,version=(iae1_0.version+1)
