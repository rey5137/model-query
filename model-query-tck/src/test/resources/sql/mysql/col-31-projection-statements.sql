select count(*) from customers ce1_0
select ce1_0.id,case when ce1_0.country=? then ce1_0.name else cast(? as char) end from customers ce1_0 order by 1 limit ?,?
select ce1_0.id from customers ce1_0 order by 1 limit ?,?
select ce1_0.id,case when ce1_0.country=? then ce1_0.name else cast(? as char) end from customers ce1_0 where ce1_0.id in (?,?,?,?,?,?) order by 1
select ce1_0.id,case when ce1_0.country=? then ce1_0.name else cast(? as char) end from customers ce1_0 where ce1_0.id=? limit ?
select ce1_0.id,case when ce1_0.country=? then ce1_0.name else cast(? as char) end from customers ce1_0 where ce1_0.id=? limit ?
select ce1_0.id,case when ce1_0.country=? then ce1_0.name else cast(? as char) end from customers ce1_0 where ce1_0.id in (?,?)
