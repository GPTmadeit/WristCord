import re, sys, json
lines = open(sys.argv[1], encoding='utf-8').read().split('\n')
fld = re.compile(r"\{'no': (\d+), 'name': '([^']+)', 'kind': '(\w+)'(.*)\}")
tname = re.compile(r"= '(discord_protos\.[\w.]+)';")
slot_assign = re.compile(r"var (_closure0_slot\d+) = r\d+;")
ret = re.compile(r"r1 = r1\.(\w+);|return (_closure0_slot\d+);")
msgs, cur, slots = [], [], {}
pending_name = None
in_T = False
for i, ln in enumerate(lines):
    m = fld.search(ln)
    if m:
        cur.append({'no': int(m.group(1)), 'name': m.group(2), 'kind': m.group(3), 'extra': m.group(4).strip(', ')})
        in_T = "'T': null" in ln
        continue
    if in_T and cur:
        r = ret.search(ln)
        if r:
            cur[-1]['T'] = r.group(1) or r.group(2)
            in_T = False
    m = tname.search(ln)
    if m and cur:
        msgs.append({'type': m.group(1), 'fields': cur})
        cur = []
        pending_name = m.group(1)
        continue
    if pending_name:
        s = slot_assign.search(ln)
        if s:
            slots[s.group(1)] = pending_name
            pending_name = None
for msg in msgs:
    for f in msg['fields']:
        t = f.get('T')
        if t in slots:
            f['T'] = slots[t].rsplit('.', 1)[-1]
want = sys.argv[2] if len(sys.argv) > 2 else None
for msg in msgs:
    short = msg['type'].rsplit('.', 1)[-1]
    if want and not re.search(want, short):
        continue
    print(short)
    for f in msg['fields']:
        print('   %3d %-32s %-8s %s %s' % (f['no'], f['name'], f['kind'], f.get('T', ''), f['extra']))
