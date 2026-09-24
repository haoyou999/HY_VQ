import subprocess, time, os, sys

def tcp_snapshot():
    out = {}
    try:
        with open('/proc/net/tcp') as f:
            for line in f.readlines()[1:]:
                parts = line.split()
                if len(parts) > 3:
                    out[(parts[1], parts[2])] = parts[3]
    except Exception:
        pass
    return out

def ip_hex(hexstr):
    # 小端 32 位 hex -> a.b.c.d
    try:
        n = int(hexstr, 16)
        return '%d.%d.%d.%d' % (n & 0xFF, (n >> 8) & 0xFF, (n >> 16) & 0xFF, (n >> 24) & 0xFF)
    except Exception:
        return hexstr

before = tcp_snapshot()
print('baseline conns:', len(before))
p = subprocess.Popen(['/data/local/tmp/yhyc547'], cwd='/data/local/tmp',
                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
start = time.time()
seen = {}
while time.time() - start < 20:
    snap = tcp_snapshot()
    for k, st in snap.items():
        if k not in before and k not in seen:
            local, rem = k
            print('[NEW] local=%s:%s -> remote=%s:%s state=%s' % (
                ip_hex(local.split(':')[0]), int(local.split(':')[1], 16),
                ip_hex(rem.split(':')[0]), int(rem.split(':')[1], 16), st))
            seen[k] = st
    time.sleep(0.3)
try:
    out, _ = p.communicate(timeout=8)
except Exception:
    p.kill()
    out, _ = p.communicate()
print('=== yhyc output ===')
print(out.decode('utf-8', 'replace')[:3000])
print('=== exit code:', p.returncode)
