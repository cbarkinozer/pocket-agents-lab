import hashlib,json,subprocess,tarfile,time
from pathlib import Path
w=Path('/workspace'); root=w/'reflex-decision'
raw=json.loads(subprocess.check_output(['vastai','show','instances','--raw']))
rows=raw if isinstance(raw,list) else raw.get('instances',[raw])
keys=['id','actual_status','ssh_host','ssh_port','gpu_name','num_gpus','host_id','machine_id','dph_total','dph_base','storage_cost','storage_total_cost','duration','start_date','inet_up_cost','inet_down_cost','inet_up_billed','inet_down_billed','inet_up','inet_down']
cost={'captured_utc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'instances':[{k:r.get(k) for k in keys} for r in rows]}
(w/'evaluation-cost-final-snapshot.json').write_text(json.dumps(cost,indent=2)+'\n')
subprocess.run(['nvidia-smi','-q'],stdout=(w/'evaluation-gpu-details.txt').open('w'),check=True)
excluded={'.venv','cache','unsloth_compiled_cache','__pycache__','.git'}
files=[p for p in root.rglob('*') if p.is_file() and not any(x in excluded for x in p.relative_to(root).parts) and p.name!='.env']
files += [w/n for n in ['setup-evaluation.log','setup_eval.sh','launch_heldout_20261014.sh','heldout-launch.log','base-download.log','run_heldout_evals.sh','evaluation-cost-snapshot.json','evaluation-cost-final-snapshot.json','evaluation-gpu-details.txt','cost_snapshot.py','closeout_evaluation.py'] if (w/n).is_file()]
items=[]
for p in sorted(files):
 digest=hashlib.sha256(p.read_bytes()).hexdigest()
 items.append({'path':p.relative_to(w).as_posix(),'bytes':p.stat().st_size,'sha256':digest})
manifest={'files':items,'excluded':['virtualenv','download/compiled caches','credentials','redundant transfer archives'],'restore':'Extract under /workspace; recreate venv with setup_eval.sh. HF base snapshot is recorded in base-download.log. Original full training archive is retained locally.','wandb':'No W&B runs created for evaluation.'}
m=w/'evaluation-closeout-inventory.json';m.write_text(json.dumps(manifest,indent=2)+'\n')
a=w/'heldout-closeout-20261014.tar.gz'
with tarfile.open(a,'w:gz') as t:
 for p in files+[m]: t.add(p,arcname=p.relative_to(w).as_posix(),recursive=False)
print(json.dumps({'archive':a.name,'bytes':a.stat().st_size,'sha256':hashlib.sha256(a.read_bytes()).hexdigest(),'file_count':len(items),'cost':cost},indent=2))
