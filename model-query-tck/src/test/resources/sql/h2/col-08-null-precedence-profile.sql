select nse1_0.id,nse1_0.sort_int from nullable_sort_rows nse1_0 order by 2,1 fetch first ? rows only
select nse1_0.id,nse1_0.sort_int from nullable_sort_rows nse1_0 order by 2 asc nulls last,1 fetch first ? rows only
select nse1_0.id,nse1_0.sort_int from nullable_sort_rows nse1_0 order by 2 desc nulls first,1 fetch first ? rows only
select nse1_0.id,nse1_0.sort_int from nullable_sort_rows nse1_0 order by 2 desc,1 fetch first ? rows only
select nse1_0.id,nse1_0.sort_int from nullable_sort_rows nse1_0 order by case when nse1_0.sort_int is null then ? else ? end,2,1 fetch first ? rows only
select nse1_0.id,nse1_0.sort_int from nullable_sort_rows nse1_0 order by case when nse1_0.sort_int is null then ? else ? end desc,2,1 fetch first ? rows only
select nse1_0.id,nse1_0.sort_int from nullable_sort_rows nse1_0 order by case when nse1_0.sort_int is null then ? else ? end,2 desc,1 fetch first ? rows only
select nse1_0.id,nse1_0.sort_int from nullable_sort_rows nse1_0 order by case when nse1_0.sort_int is null then ? else ? end desc,2 desc,1 fetch first ? rows only
