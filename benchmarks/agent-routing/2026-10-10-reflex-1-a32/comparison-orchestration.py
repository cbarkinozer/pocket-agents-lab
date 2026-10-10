import json,time,pathlib,subprocess,urllib.request,sys
root=pathlib.Path("/Users/cbarkinozer/Documents/GitHub/pocket-agents-lab")
out=root/"benchmarks/agent-routing/2026-10-10-reflex-1-a32"
adb="/Users/cbarkinozer/.cache/reflex-android/platform-tools/adb"
while True:
 d=json.loads((out/"reflex-1-results.json").read_text())
 if d.get("complete"):break
 time.sleep(10)
print("Reflex complete; switching models",flush=True)
subprocess.run([adb,"shell","kill -INT 16594"],check=True)
time.sleep(2)
sha=subprocess.check_output([adb,"shell","sha256sum /sdcard/Download/models/Qwen3.5-0.8B-Q4_K_M.gguf"],text=True).split()[0]
cmd="cd /data/local/tmp/reflex-runtime; LD_LIBRARY_PATH=. ./llama-server -m /sdcard/Download/models/Qwen3.5-0.8B-Q4_K_M.gguf -c 2048 -t 4 -tb 4 -ngl 0 --fit off -fa off --no-repack --host 127.0.0.1 --port 8091 --jinja"
with (out/"qwen-server.log").open("w") as log:
 server=subprocess.Popen([adb,"shell",cmd],stdout=log,stderr=subprocess.STDOUT)
 try:
  for attempt in range(60):
   if server.poll() is not None:raise RuntimeError("Baseline server exited")
   try:
    with urllib.request.urlopen("http://127.0.0.1:8091/health",timeout=2) as r:
     if json.load(r).get("status")=="ok":break
   except Exception:pass
   time.sleep(1)
  else:raise RuntimeError("Baseline load timeout")
  pid=subprocess.check_output([adb,"shell","pidof llama-server"],text=True).strip()
  print("Baseline loaded",pid,sha,flush=True)
  (out/"qwen-model-sha256.txt").write_text(sha+"\n")
  with (out/"qwen-benchmark.log").open("w") as benchlog:
   subprocess.run([sys.executable,str(root/"tools/benchmark_decision_android.py"),"--adb",adb,"--serial","R68T800LZ0H","--mode","chat","--label","Qwen3.5-0.8B-Q4_K_M","--model-sha256",sha,"--runtime-revision","f0c41e016-b11443","--pid",pid,"--out",str(out/"qwen-results.json")],cwd=root,stdout=benchlog,stderr=subprocess.STDOUT,check=True)
 finally:
  if server.poll() is None:
   subprocess.run([adb,"shell","pkill -INT -x llama-server"])
   try:server.wait(timeout=15)
   except subprocess.TimeoutExpired:server.terminate()
print("Matched baseline complete",flush=True)
