// Code node "Expand candidates": one item per message worth reading in full.
return $('Triage').first().json.candidates.map((c) => ({ json: c }));
