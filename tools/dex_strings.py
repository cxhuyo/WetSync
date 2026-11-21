import zipfile, struct, sys, re

def uleb(data, off):
    r = s = 0
    while True:
        b = data[off]; off += 1
        r |= (b & 0x7f) << s
        if not b & 0x80:
            break
        s += 7
    return r, off

def strings(data):
    ss, so = struct.unpack_from('<II', data, 0x38)
    out = []
    for i in range(ss):
        p = struct.unpack_from('<I', data, so + i * 4)[0]
        ln, q = uleb(data, p)
        try:
            out.append(data[q:q + ln].decode('utf-8', 'replace'))
        except Exception:
            out.append('')
    return out

apk = sys.argv[1]
kw = sys.argv[2] if len(sys.argv) > 2 else ''
cls_only = len(sys.argv) > 3 and sys.argv[3] == 'cls'

z = zipfile.ZipFile(apk)
seen = set()
for n in [x for x in z.namelist() if x.endswith('.dex')]:
    for s in strings(z.read(n)):
        if kw and kw.lower() not in s.lower():
            continue
        if cls_only and not (s.startswith('L') and s.endswith(';')):
            continue
        seen.add(s)

print('total:', len(seen))
for s in sorted(seen)[:120]:
    print(' ', s)
