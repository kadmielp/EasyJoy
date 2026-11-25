import socket, struct, time, ctypes, sys
from ctypes import wintypes
PORT = 47820
HOST = "127.0.0.1"
def hdr(t): return b"EJ\x01" + bytes([t])
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(2)
ok = True
def check(name, cond, extra=""):
    global ok
    print(("PASS " if cond else "FAIL ") + name, extra); ok &= bool(cond)

s.sendto(hdr(0x01), (HOST, PORT)); d, _ = s.recvfrom(1024)
check("discover->announce", d[:4] == hdr(0x02), d[4:].decode())
s.sendto(hdr(0x05) + "TestPhone".encode(), (HOST, PORT)); d, _ = s.recvfrom(1024)
check("ping->pong driver ok", d[:5] == hdr(0x06) + b"\x00", d.hex())

class XGP(ctypes.Structure):
    _fields_ = [("wButtons", wintypes.WORD), ("bLeftTrigger", ctypes.c_ubyte), ("bRightTrigger", ctypes.c_ubyte),
                ("sThumbLX", ctypes.c_short), ("sThumbLY", ctypes.c_short), ("sThumbRX", ctypes.c_short), ("sThumbRY", ctypes.c_short)]
class XS(ctypes.Structure): _fields_ = [("dwPacketNumber", wintypes.DWORD), ("Gamepad", XGP)]
xi = ctypes.windll.xinput1_4
def pads():
    out = {}
    for i in range(4):
        st = XS()
        if xi.XInputGetState(i, ctypes.byref(st)) == 0: out[i] = st.Gamepad
    return out
before = set(pads())
def gp(seq, buttons, lt, rt, lx, ly, rx, ry):
    return hdr(0x10) + struct.pack("<HHBBhhhh", seq, buttons, lt, rt, lx, ly, rx, ry)
A, DUP, LB = 0x1000, 0x0001, 0x0100
for i in range(5):
    s.sendto(gp(i+1, A|DUP|LB, 255, 0, 20000, -12000, 0, 32767), (HOST, PORT)); time.sleep(0.05)
time.sleep(0.8)
now = pads(); new = [i for i in now if i not in before]
check("virtual pad appeared", len(new) == 1, f"slots before={sorted(before)} after={sorted(now)}")
if new:
    g = now[new[0]]
    check("buttons", g.wButtons == A|DUP|LB, hex(g.wButtons))
    check("triggers", (g.bLeftTrigger, g.bRightTrigger) == (255, 0))
    check("sticks", (g.sThumbLX, g.sThumbLY, g.sThumbRX, g.sThumbRY) == (20000, -12000, 0, 32767), (g.sThumbLX, g.sThumbLY, g.sThumbRX, g.sThumbRY))
    # stale seq must be dropped
    s.sendto(gp(2, 0, 0, 0, 0, 0, 0, 0), (HOST, PORT)); time.sleep(0.3)
    check("old seq ignored", pads()[new[0]].wButtons == A|DUP|LB)
    s.sendto(gp(10, 0x8000, 0, 0, 0, 0, 0, 0), (HOST, PORT)); time.sleep(0.3)
    check("newer seq applied", pads()[new[0]].wButtons == 0x8000)
s.sendto(hdr(0x30), (HOST, PORT)); time.sleep(1.0)
check("bye removes pad", set(pads()) == before, sorted(pads()))

class PT(ctypes.Structure): _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]
def cur():
    p = PT(); ctypes.windll.user32.GetCursorPos(ctypes.byref(p)); return (p.x, p.y)
p0 = cur()
s.sendto(hdr(0x20) + struct.pack("<hh", 40, 30), (HOST, PORT)); time.sleep(0.3)
p1 = cur()
check("mouse moved", p1 != p0, f"{p0} -> {p1}")
s.sendto(hdr(0x20) + struct.pack("<hh", -40, -30), (HOST, PORT)); time.sleep(0.2)
s.sendto(b"garbage", (HOST, PORT)); s.sendto(hdr(0x10) + b"\x01", (HOST, PORT)); time.sleep(0.2)
s.sendto(hdr(0x05), (HOST, PORT)); d, _ = s.recvfrom(1024)
check("survives malformed packets", d[:4] == hdr(0x06))
print("ALL OK" if ok else "SOME FAILED"); sys.exit(0 if ok else 1)
