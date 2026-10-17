import json, os, time
from dataclasses import dataclass, asdict, field
AGENT="agent"; HUMAN="human"; P=os.environ["FAKE_LEASE"]
@dataclass
class Lease:
    holder: str = AGENT; viewer_id: str = None; since: float = field(default_factory=time.time); reason: str = ""; epoch: int = 0
def _r():
    return Lease(**json.load(open(P))) if os.path.exists(P) else Lease()
def _w(l): json.dump(asdict(l), open(P,"w"))
def public_view(l):
    d=asdict(l); d["viewer_id"]=None; return d
def get(): return _r()
def acquire(v, reason=""):
    l=_r(); l.holder=HUMAN; l.viewer_id=v; l.reason=reason; l.epoch+=1; _w(l); return l
def release(v=None):
    l=_r()
    if v is not None and l.holder==HUMAN and l.viewer_id!=v: return l
    if l.holder==AGENT: return l
    l.holder=AGENT; l.viewer_id=None; l.epoch+=1; _w(l); return l
