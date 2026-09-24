import struct, hashlib, re

p = '/data/local/tmp/folk.apk'
d = open(p, 'rb').read()
idx = d.rfind(b'APK Sig Block 42')
size = struct.unpack('<Q', d[idx-8:idx])[0]
off = idx - size + 16
v2 = None
while off < idx - 16:
    ln = struct.unpack('<Q', d[off:off+8])[0]
    vid = struct.unpack('<I', d[off+8:off+12])[0]
    if vid == 0x7109871a:
        v2 = d[off+12:off+8+ln]
        break
    off += 8 + ln
print('v2 block len:', len(v2) if v2 else None)

# 扫描 v2 块找 X.509 证书（SEQUENCE 0x30 0x82 len）
found = 0
for i in range(len(v2) - 5):
    if v2[i] == 0x30 and v2[i+1] == 0x82:
        ln = (v2[i+2] << 8) | v2[i+3]
        if 200 < ln < 3000 and i + 4 + ln <= len(v2):
            cert = v2[i:i+4+ln]
            print('cert#%d DER len=%d' % (found, len(cert)))
            print('  SHA256:', hashlib.sha256(cert).hexdigest().upper())
            print('  SHA1  :', hashlib.sha1(cert).hexdigest().upper())
            print('  strs  :', [s.decode('latin1') for s in re.findall(rb'[ -~]{6,}', cert)[:8]])
            found += 1
            if found >= 3:
                break
print('total certs found:', found)
