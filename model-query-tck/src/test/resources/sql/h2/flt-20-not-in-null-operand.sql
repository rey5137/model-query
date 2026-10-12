select cre1_0.id,cre1_0.status,cre1_0.rank_no,cre1_0.active,cre1_0.label from constant_rows cre1_0 where cre1_0.label not in ('plain',nullif(cre1_0.label,?)) or cre1_0.label is null order by 1
select cre1_0.id,cre1_0.status,cre1_0.rank_no,cre1_0.active,cre1_0.label from constant_rows cre1_0 where cre1_0.label not in ('plain','x') or cre1_0.label is null order by 1
