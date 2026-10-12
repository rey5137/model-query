select cre1_0.id,cre1_0.status,cre1_0.rank_no,cre1_0.active,cre1_0.label from constant_rows cre1_0 where cre1_0.status in ('PENDING','FAILED') order by 1
select cre1_0.id,cre1_0.status,cre1_0.rank_no,cre1_0.active,cre1_0.label from constant_rows cre1_0 where cre1_0.status in ('PENDING','FAILED','CLOSED') order by 1
select cre1_0.id,cre1_0.status,cre1_0.rank_no,cre1_0.active,cre1_0.label from constant_rows cre1_0 where cre1_0.status not in ('PENDING','CLOSED') or cre1_0.status is null order by 1
