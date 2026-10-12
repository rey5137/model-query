select cre1_0.id,cre1_0.status,cre1_0.rank_no,cre1_0.active,cre1_0.label from constant_rows cre1_0 where cre1_0.status<>'CLOSED' order by 1
select cre1_0.id,cre1_0.status,cre1_0.rank_no,cre1_0.active,cre1_0.label from constant_rows cre1_0 where cre1_0.rank_no=1 order by 1
select cre1_0.id,cre1_0.status,cre1_0.rank_no,cre1_0.active,cre1_0.label from constant_rows cre1_0 where cre1_0.active='Y' order by 1
select cre1_0.id,cre1_0.status,cre1_0.rank_no,cre1_0.active,cre1_0.label from constant_rows cre1_0 where cre1_0.label='padded' order by 1
