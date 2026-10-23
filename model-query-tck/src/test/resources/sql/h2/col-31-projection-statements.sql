select count(*) from customers ce1_0
select ce1_0.id,case when ce1_0.country=? then ce1_0.name else cast(? as varchar) end from customers ce1_0 order by 1 offset ? rows fetch first ? rows only
select ce1_0.id from customers ce1_0 order by 1 offset ? rows fetch first ? rows only
select ce1_0.id,case when ce1_0.country=? then ce1_0.name else cast(? as varchar) end from customers ce1_0 where ce1_0.id in (?,?,?,?,?,?) order by 1
select ce1_0.id,case when ce1_0.country=? then ce1_0.name else cast(? as varchar) end from customers ce1_0 where ce1_0.id=? fetch first ? rows only
select ce1_0.id,case when ce1_0.country=? then ce1_0.name else cast(? as varchar) end from customers ce1_0 where ce1_0.id=? fetch first ? rows only
select ce1_0.id,case when ce1_0.country=? then ce1_0.name else cast(? as varchar) end from customers ce1_0 where ce1_0.id in (?,?)
