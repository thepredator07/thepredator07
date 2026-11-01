SELECT
  (SELECT value FROM settings WHERE key = 'discovery')         AS cfg,
  (SELECT value FROM settings WHERE key = 'candidate_profile') AS profile,
  COALESCE((
    SELECT json_agg(json_build_object(
             'id', s.id, 'company_id', s.company_id, 'company_name', c.name, 'company_category', c.category,
             'ats', s.ats, 'board_token', s.board_token, 'endpoint_url', s.endpoint_url, 'search_text', s.search_text)
           ORDER BY s.id)
    FROM job_sources s JOIN companies c ON c.id = s.company_id
    WHERE s.is_active AND c.is_target), '[]'::json) AS sources;
