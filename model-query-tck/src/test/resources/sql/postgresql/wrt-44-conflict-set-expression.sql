insert into ins_assigned as iae1_0(id,code,name,version) values (?,?,?,?) on conflict(id) do update set name=(iae1_0.name||excluded.name),code=?,version=(iae1_0.version+?)
