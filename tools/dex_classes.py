"""导出指定类的字节码（smali 级），用于分析剪贴板图片处理逻辑。"""
import sys, zipfile
from loguru import logger
logger.remove()                      # androguard 会刷 DEBUG 日志，先掐掉
from androguard.core.dex import DEX

apk, target = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(apk)

dexes = [n for n in z.namelist() if n.endswith('.dex')]
found = False
for n in dexes:
    raw = z.read(n)
    try:
        d = DEX(raw)
    except Exception as e:
        print('skip', n, e)
        continue
    full = target if target.startswith('L') else 'L' + target + ';'
    cls = None
    for c in d.get_classes():
        if c.get_name() == full:
            cls = c
            break
    if not cls:
        continue
    found = True
    print('=== %s in %s ===' % (target, n))
    print('  super: %s' % cls.get_superclassname())
    print('  interfaces:', cls.get_interfaces())
    print('  fields:', [f.get_name() for f in cls.get_fields()])
    print('  methods:')
    for m in cls.get_methods():
        print('    --- %s %s' % (m.get_name(), m.get_descriptor()))
    print()
    if len(sys.argv) > 3:
        for m in cls.get_methods():
            if m.get_name() == sys.argv[3]:
                print('--- code of %s ---' % sys.argv[3])
                for ins in m.get_instructions():
                    print('  %-28s %s' % (ins.get_name(), ins.get_output()))

if not found:
    print('class not found:', target)
