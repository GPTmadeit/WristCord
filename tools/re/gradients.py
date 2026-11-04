"""Extract Nitro client-theme gradient presets from ClientThemesConstants."""
import io, json, os, re
from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from hermes_dec.parsers.serialized_literal_parser import unpack_slp_array

B = os.environ.get('HQ_BUNDLE', os.path.expanduser('~/re/discord-android-351.11/apk/assets/index.android.bundle'))
OUT = os.environ.get('HQ_OUT', os.path.expanduser('~/re/discord-android-351.11/out'))
r = HBCReader(); r.read_whole_file(io.BufferedReader(io.BytesIO(open(B, 'rb').read())))

def lit(ins):
    keys = r.object_shape_keys[ins.arg2]
    vals = unpack_slp_array(r.literal_values[ins.arg3:], len(keys), r.header.version)
    o = {}
    for k, it in zip(keys, vals.items):
        v = it.value
        if it.tag_type.name.endswith('StringTag'):
            v = r.strings[v]
        o[k.strip("'\"")] = v
    return o

regs, presets, stack = {}, [], {}
for ins in parse_hbc_bytecode(r.function_headers[1256], r):
    n = ins.inst.name
    if n.startswith('NewObjectWithBuffer'):
        regs[ins.arg1] = lit(ins)
        if 'midpointPercentage' in regs[ins.arg1]:
            presets.append(regs[ins.arg1])
    elif n.startswith('NewArray'):
        regs[ins.arg1] = []
    elif n.startswith('LoadConst') and n not in ('LoadConstString', 'LoadConstStringLongIndex'):
        regs[ins.arg1] = {'LoadConstZero': 0, 'LoadConstTrue': True, 'LoadConstFalse': False,
                          'LoadConstNull': None, 'LoadConstUndefined': None}.get(n, getattr(ins, 'arg2', None))
    elif n in ('LoadConstString', 'LoadConstStringLongIndex'):
        regs[ins.arg1] = r.strings[ins.arg2]
    elif n in ('GetById', 'GetByIdShort', 'GetByIdLong'):
        regs[ins.arg1] = ('id', r.strings[ins.arg4])
    elif n in ('Mov', 'MovLong'):
        regs[ins.arg1] = regs.get(ins.arg2)
    elif n in ('PutOwnByIndex', 'PutOwnByIndexL', 'DefineOwnByIndex', 'DefineOwnByIndexL'):
        arr = regs.get(ins.arg1)
        if isinstance(arr, list):
            arr.append(regs.get(ins.arg2))
    elif n in ('PutOwnBySlotIdx', 'PutOwnBySlotIdxLong'):
        o = regs.get(ins.arg1)
        if isinstance(o, dict):
            keys = list(o.keys())
            if ins.arg3 < len(keys):
                o[keys[ins.arg3]] = regs.get(ins.arg2)
out = []
for p in presets:
    q = {k: v for k, v in p.items() if k != 'getName'}
    out.append(q)
# Ids and base themes come out as enum references; the colour stops are read from the
# decompiled module text, which keeps them as literal objects.
dec = os.environ.get('HQ_DEC_1256', OUT + '/dec/m1256.js')
src = open(dec, encoding='utf-8').read().split('\n') if os.path.exists(dec) else []
groups, cur = [], None
for ln in src:
    if "'midpointPercentage'" in ln:
        cur = []; groups.append(cur)
    m = re.search(r"\{'token': '(\w+)', 'stop': ([\d.]+)\}", ln)
    if m and cur is not None:
        cur.append((m.group(1), float(m.group(2))))
raw = json.load(open(OUT + '/raw-colors.json'))
IDS = ['MINT_APPLE','CITRUS_SHERBERT','RETRO_RAINCLOUD','HANAMI','SUNRISE','COTTON_CANDY','LOFI_VIBES','DESERT_KHAKI','SUNSET','CHROMA_GLOW','FOREST','CRIMSON_MOON','MIDNIGHT_BLURPLE','MARS','DUSK','UNDER_THE_SEA','EASTER_EGG','RETRO_STORM','NEON_NIGHTS','SEPIA','STRAWBERRY_LEMONADE','AURORA','BLURPLE_TWILIGHT']
final = []
for p, g in zip(out, groups):
    name = p['id'][1] if isinstance(p['id'], tuple) else p['id']
    final.append({'name': name, 'protoId': IDS.index(name) if name in IDS else None,
                  'base': p['theme'][1] if isinstance(p['theme'], tuple) else p['theme'],
                  'angle': p['angle'], 'midpoint': p['midpointPercentage'],
                  'stops': [{'token': t, 'hex': raw.get(t), 'stop': s} for t, s in g]})
json.dump(final, open(OUT + '/gradient-presets.json', 'w'), indent=1)
print(len(final), 'gradient presets')
