-- Seed data: candidate profile, Module A settings, starter target companies.
-- Re-runnable: settings are upserted, companies/sources are inserted only if missing.
--
-- IMPORTANT: the ATS board tokens below are best-effort guesses and are marked verified=false.
-- Run scripts/verify-sources.sh from your own machine (it calls each public job-board API once);
-- the discovery workflow also marks a source verified on its first successful fetch and
-- deactivates it after `max_source_failures` consecutive failures.

BEGIN;

INSERT INTO settings (key, value) VALUES
('candidate_profile', $json${
  "current_employer": "Jio Platforms Limited",
  "current_designation": "SDE-1",
  "years_experience": 3,
  "target_roles": ["Backend Engineer","Java Developer","Software Development Engineer","SDE-1","SDE-2","Platform Engineer","Distributed Systems Engineer"],
  "skills_core": ["Java 17/21","Spring Boot","Spring Security","Spring Data JPA / Hibernate","Microservices","REST APIs","Apache Kafka","Redis","Distributed Systems","Asynchronous Processing","Event-Driven Architecture","System Design"],
  "skills_secondary": ["MongoDB","MySQL","Oracle","PostgreSQL","Elasticsearch","Docker","Kubernetes","AWS","Azure"],
  "achievements": [
    "Built asynchronous validation frameworks",
    "Improved validation latency by approximately 90%",
    "Increased throughput through parallel rule execution",
    "Designed Kafka CDC pipelines",
    "Developed asynchronous orchestration frameworks",
    "Worked on cloud disaster recovery automation",
    "Built backend services for enterprise cloud platforms"
  ],
  "target_ctc_lpa": {"min": 15, "max": 20},
  "preferred_locations": ["Mumbai","Bengaluru","Hyderabad","Pune","Remote (India)"],
  "notice_period_days": 60,
  "notice_period_negotiable_to_days": 45,
  "company_preference_order": ["product","fintech","saas_cloud","engineering_led"],
  "target_joining_window": "November 2026 - January 2027"
}$json$::jsonb),
('discovery', $json${
  "anthropic_base_url": "https://api.anthropic.com",
  "scoring_model": "claude-opus-5-5",
  "scoring_effort": "low",
  "prompt_version": "score-v1",
  "max_job_age_days": 30,
  "max_jobs_scored_per_run": 60,
  "max_description_chars": 24000,
  "max_source_failures": 5,
  "workday_page_size": 20,
  "title_include_regex": "(back[- ]?end|java|spring|software (development )?engineer|software developer|\\bsde\\b|\\bswe\\b|platform engineer|distributed|microservice|member of technical staff|\\bmts\\b|server[- ]side|api engineer|engineer\\s*(i|ii|2|1)\\b)",
  "title_exclude_regex": "(intern\\b|internship|trainee|apprentice|principal|\\bstaff\\b|director|head of|\\bvp\\b|vice president|manager|front[- ]?end|android|\\bios\\b|mobile|react|angular|\\bui\\b|\\bux\\b|\\bqa\\b|sdet|\\btest|quality|data scientist|machine learning|\\bml\\b|data analyst|analyst|sales|support engineer|solutions? (engineer|consultant)|salesforce|\\bsap\\b|embedded|firmware|hardware|\\.net|c#|php|designer|recruiter)",
  "target_cities": {
    "mumbai": ["mumbai","bombay","navi mumbai","thane","powai"],
    "bengaluru": ["bengaluru","bangalore"],
    "hyderabad": ["hyderabad","secunderabad"],
    "pune": ["pune"]
  },
  "india_markers": ["india","delhi","gurgaon","gurugram","noida","chennai","kolkata","ahmedabad","jaipur","kochi","coimbatore","indore","chandigarh"],
  "foreign_markers": ["united states","\\busa?\\b","canada","united kingdom","\\buk\\b","london","germany","berlin","ireland","dublin","netherlands","amsterdam","france","paris","spain","poland","singapore","australia","japan","brazil","mexico","emea","americas","\\bus only","north america","europe","israel","dubai","uae","philippines","vietnam","indonesia","malaysia"]
}$json$::jsonb),
('notifications', '{"telegram_chat_id": null, "daily_brief_hour_ist": 8}'::jsonb)
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now();

-- Starter target companies. Edit freely: category drives the product-company score, priority is for you.
INSERT INTO companies (name, category, priority) VALUES
 ('Razorpay','fintech',1), ('PhonePe','fintech',1), ('CRED','fintech',1), ('Groww','fintech',2),
 ('Zeta','fintech',2), ('Slice','fintech',3), ('Stripe','fintech',1), ('Coinbase','fintech',2),
 ('PayPal','fintech',2), ('Mastercard','fintech',3), ('Visa','fintech',3),
 ('Postman','saas_cloud',1), ('Databricks','saas_cloud',1), ('Confluent','saas_cloud',1),
 ('MongoDB','saas_cloud',1), ('Elastic','saas_cloud',2), ('Rubrik','saas_cloud',2),
 ('Cloudflare','saas_cloud',2), ('Okta','saas_cloud',3), ('Freshworks','saas_cloud',2),
 ('Atlassian','saas_cloud',1), ('GitLab','saas_cloud',2), ('Rippling','saas_cloud',2),
 ('Meesho','product',2), ('Zepto','product',2), ('Swiggy','product',2), ('Walmart Global Tech','product',3),
 ('Salesforce','saas_cloud',3), ('Adobe','product',3), ('Uber','product',1)
ON CONFLICT (normalized_name) DO NOTHING;

-- (ats, board_token). Workday tokens are "host|tenant|site" from the career-site URL
-- https://<host>/en-US/<site>  with tenant = first label of host.
INSERT INTO job_sources (company_id, ats, board_token, search_text, is_active)
SELECT c.id, s.ats, s.token, s.search_text, s.ats <> 'workday'  -- Workday: opt in per company (see docs)
FROM (VALUES
 ('Razorpay','greenhouse','razorpaysoftwareprivatelimited',NULL),
 ('PhonePe','greenhouse','phonepe',NULL),
 ('Groww','greenhouse','groww',NULL),
 ('Stripe','greenhouse','stripe',NULL),
 ('Coinbase','greenhouse','coinbase',NULL),
 ('Postman','greenhouse','postman',NULL),
 ('Databricks','greenhouse','databricks',NULL),
 ('MongoDB','greenhouse','mongodb',NULL),
 ('Elastic','greenhouse','elastic',NULL),
 ('Rubrik','greenhouse','rubrik',NULL),
 ('Cloudflare','greenhouse','cloudflare',NULL),
 ('Okta','greenhouse','okta',NULL),
 ('GitLab','greenhouse','gitlab',NULL),
 ('CRED','lever','cred',NULL),
 ('Zeta','lever','zeta',NULL),
 ('Slice','lever','sliceit',NULL),
 ('Meesho','lever','meesho',NULL),
 ('Atlassian','lever','atlassian',NULL),
 ('Confluent','ashby','confluent',NULL),
 ('Rippling','ashby','rippling',NULL),
 ('Zepto','ashby','zepto',NULL),
 ('Freshworks','smartrecruiters','Freshworks',NULL),
 ('Visa','smartrecruiters','Visa',NULL),
 ('PayPal','workday','paypal.wd1.myworkdayjobs.com|paypal|jobs','java'),
 ('Mastercard','workday','mastercard.wd1.myworkdayjobs.com|mastercard|CorporateCareers','java'),
 ('Walmart Global Tech','workday','walmart.wd5.myworkdayjobs.com|walmart|WalmartExternal','java backend'),
 ('Salesforce','workday','salesforce.wd12.myworkdayjobs.com|salesforce|External_Career_Site','java'),
 ('Adobe','workday','adobe.wd5.myworkdayjobs.com|adobe|external_experienced','java')
) AS s(company, ats, token, search_text)
JOIN companies c ON c.name = s.company
ON CONFLICT (ats, board_token) DO NOTHING;

COMMIT;
