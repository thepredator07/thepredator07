-- $1 = [{id, ok, error}]
UPDATE notifications n SET sent_at = CASE WHEN r.ok THEN now() ELSE NULL END, last_error = r.error
FROM jsonb_to_recordset($1::jsonb) AS r(id bigint, ok boolean, error text)
WHERE n.id = r.id;
SELECT count(*) FILTER (WHERE (x->>'ok')::boolean) AS sent FROM jsonb_array_elements($1::jsonb) x;
