import os, re, sys

pid = int(sys.argv[1]) if len(sys.argv) > 1 else 3433
regions = []
with open('/proc/%d/maps' % pid) as f:
    for line in f:
        parts = line.split()
        if len(parts) < 2:
            continue
        addr = parts[0].split('-')
        perms = parts[1]
        if 'r' in perms:
            regions.append((int(addr[0], 16), int(addr[1], 16), parts[-1] if len(parts) > 5 else ''))

print('readable regions:', len(regions))
found = {}
try:
    mem = open('/proc/%d/mem' % pid, 'rb')
    for start, end, name in regions:
        if end - start > 300 * 1024 * 1024:
            continue
        try:
            mem.seek(start)
            data = mem.read(end - start)
            for m in re.finditer(rb'[a-zA-Z0-9_][a-zA-Z0-9_.-]*\.(?:com|cn|net|org|asia|cc|top|xyz|icu|vip|club|info|io|dev|app|cloud|work|site|online|store|fun|live|pro|link|me|biz|moe|co|tv|tech|cloud|space|website|icu)(?:\.[a-z]{2})?', data):
                s = m.group().decode('latin1')
                if 6 < len(s) < 60 and not s.startswith('com.') and '..' not in s and not s.startswith('www.'):
                    found[s] = found.get(s, 0) + 1
        except (OSError, ValueError):
            pass
    mem.close()
except Exception as e:
    print('mem open error:', e)

for s, c in sorted(found.items(), key=lambda x: -x[1]):
    print('%4d  %s' % (c, s))
print('total unique:', len(found))