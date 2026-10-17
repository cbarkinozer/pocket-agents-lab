#!/usr/bin/env python3
"""Package the verified Android b11443 decision server alongside isolated JNI libs."""
import argparse,hashlib,json,shutil
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--runtime-dir',type=Path,required=True);a=p.parse_args()
root=Path(__file__).resolve().parents[1];out=root/'app/src/main/jniLibs/arm64-v8a';out.mkdir(parents=True,exist_ok=True)
names=['llama-server','libllama-server-impl.so','libllama-common.so','libmtmd.so','libllama.so','libggml.so','libggml-base.so']
names+=sorted(x.name for x in a.runtime_dir.glob('libggml-cpu-*.so'))
expected={f['path']:f for f in json.loads((root/'tools/reflex-runtime-b11443.json').read_text())['files_before_android_strip']}
files=[]
for name in names:
 source=a.runtime_dir/name
 if not source.is_file():raise SystemExit('Missing runtime file: '+str(source))
 dest=out/('libreflex-server.so' if name=='llama-server' else name)
 if hashlib.sha256(source.read_bytes()).hexdigest()!=expected[dest.name]['sha256']:raise SystemExit('Pinned runtime checksum mismatch: '+name)
 shutil.copy2(source,dest)
 files.append({'path':dest.name,'bytes':dest.stat().st_size,'sha256':hashlib.sha256(dest.read_bytes()).hexdigest()})
assets=root/'app/src/main/assets/reflex-runtime';assets.mkdir(parents=True,exist_ok=True)
shutil.copy2(a.runtime_dir/'LICENSE',assets/'LICENSE')
manifest={'runtime':'llama.cpp Android ARM64 b11443','revision':'f0c41e0168dfd4b5ef72b21d1a311b24cc7a894a','files_before_android_strip':files,'jni_isolation':'JNI targets use pocket- output names; decision server is a separate process with original library names.'}
(assets/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');print('Prepared',len(files),'runtime binaries')
