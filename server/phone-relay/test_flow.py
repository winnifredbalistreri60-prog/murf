# Имитация телефона: hello -> poll -> result / upload. Аргумент: базовый URL phone-порта.
import json, sys, threading, time, urllib.request
B=sys.argv[1]; A=sys.argv[2]
op=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def post(u,d,raw=False):
    r=urllib.request.Request(u,data=d if raw else json.dumps(d).encode(),headers={"Content-Type":"application/json"}); return op.open(r,timeout=60)
post(B+"/phone/api/hello",{"device":"t1","name":"test","caps":["run_shell"],"termux":True})
def phone():
    for _ in range(3):
        r=op.open(B+"/phone/api/poll?device=t1&wait=10",timeout=30)
        if r.status==204: continue
        req=json.loads(r.read())["request"]; print("phone got",req["tool"],req.get("upload_url","")[-20:])
        if req["tool"]=="pull_file":
            up=req["upload_url"].replace("https://your-server.example",B)+"?name=a.txt"
            print("upload",post(up,b"hello file",raw=True).read())
        post(B+"/phone/api/result",{"id":req["id"],"ok":True,"output":"done "+req["tool"],"exit_code":0})
threading.Thread(target=phone,daemon=True).start()
time.sleep(0.5)
for tool,args in [("run_shell",{"command":"uname -a"}),("pull_file",{"path":"/sdcard/a.txt"})]:
    r=post(A+"/call",{"tool":tool,"args":args,"timeout":20}); print("agent got",r.read().decode())
