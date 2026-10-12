select count(ce1_0.id) from customers ce1_0
select ce1_0.id,ce1_0.name,case when ce1_0.country=? then ce1_0.name else cast(? as char) end from customers ce1_0 order by 3,1 limit ?,?
select ce1_0.id from customers ce1_0 order by case when ce1_0.country=? then ce1_0.name else ? end,1 limit ?,?
select ce1_0.id,ce1_0.name,case when ce1_0.country=? then ce1_0.name else cast(? as char) end from customers ce1_0 where ce1_0.id in (?,?,?,?,?,?) order by 3,1
select ce1_0.id,ce1_0.name,case when ce1_0.country=? then ce1_0.name else cast(? as char) end from customers ce1_0 where ce1_0.id=? limit ?
select ce1_0.id,ce1_0.name,case when ce1_0.country=? then ce1_0.name else cast(? as char) end from customers ce1_0 where ce1_0.id=? limit ?
select ce1_0.id,ce1_0.name,case when ce1_0.country=? then ce1_0.name else cast(? as char) end from customers ce1_0 where ce1_0.id in (?,?)
