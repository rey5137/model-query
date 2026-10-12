select cre1_0.id,jsonb_exists(cre1_0.tags,?) from constant_rows cre1_0 where jsonb_exists(cre1_0.tags,?)=? order by 1
