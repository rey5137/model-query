select cre1_0.id,cre1_0.status,cre1_0.rank_no,cre1_0.active,cre1_0.label from constant_rows cre1_0 where cre1_0.state='C' order by 1
select cre1_0.id,cre1_0.status,cre1_0.rank_no,cre1_0.active,cre1_0.label from constant_rows cre1_0 where cre1_0.state in ('P','F') order by 1
