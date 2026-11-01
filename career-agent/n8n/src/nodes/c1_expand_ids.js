// Code node "Expand ids": drop the {success:true} placeholder the Postgres node emits when nothing is new.
return $input.all().filter((it) => it.json.id);
