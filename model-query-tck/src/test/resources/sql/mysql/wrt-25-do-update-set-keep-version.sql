insert into ins_assigned(id,code,name,version) values (?,?,?,?), (?,?,?,?) as excluded(id,code,name,version) on duplicate key update name=?
