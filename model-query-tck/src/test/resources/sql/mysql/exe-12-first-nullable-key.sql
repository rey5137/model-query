select nse1_0.id,nse1_0.sort_int from nullable_sort_rows nse1_0 where nse1_0.id in (?,?,?,?) order by case when (nse1_0.sort_int) is null then 1 else 0 end,2 limit ?
