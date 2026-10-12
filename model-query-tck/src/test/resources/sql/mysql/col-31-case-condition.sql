select ce1_0.id,case when ce1_0.country=? then ce1_0.name else cast(? as char) end from customers ce1_0 order by 1
