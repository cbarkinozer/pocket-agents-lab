import json
from pathlib import Path
raw=json.loads(Path('/workspace/vast-instance-snapshot.json').read_text())
rows=raw if isinstance(raw,list) else raw.get('instances',[raw])
keys=['id','actual_status','ssh_host','ssh_port','dph_total','duration','start_date','inet_up_cost','inet_down_cost']
out=[{k:r.get(k) for k in keys} for r in rows]
Path('/workspace/evaluation-cost-snapshot.json').write_text(json.dumps(out,indent=2)+'\n')
print(json.dumps(out,indent=2))
