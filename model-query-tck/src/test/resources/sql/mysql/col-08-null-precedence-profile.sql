select nse1_0.id,nse1_0.sort_int from nullable_sort_rows nse1_0 order by 2,1 limit ?
select nse1_0.id,nse1_0.sort_int from nullable_sort_rows nse1_0 order by case when (nse1_0.sort_int) is null then 1 else 0 end,2,1 limit ?
select nse1_0.id,nse1_0.sort_int from nullable_sort_rows nse1_0 order by case when (nse1_0.sort_int) is null then 0 else 1 end,2 desc,1 limit ?
select nse1_0.id,nse1_0.sort_int from nullable_sort_rows nse1_0 order by 2 desc,1 limit ?
select nse1_0.id,nse1_0.sort_int from nullable_sort_rows nse1_0 order by case when nse1_0.sort_int is null then ? else ? end,2,1 limit ?
select nse1_0.id,nse1_0.sort_int from nullable_sort_rows nse1_0 order by case when nse1_0.sort_int is null then ? else ? end desc,2,1 limit ?
select nse1_0.id,nse1_0.sort_int from nullable_sort_rows nse1_0 order by case when nse1_0.sort_int is null then ? else ? end,2 desc,1 limit ?
select nse1_0.id,nse1_0.sort_int from nullable_sort_rows nse1_0 order by case when nse1_0.sort_int is null then ? else ? end desc,2 desc,1 limit ?
