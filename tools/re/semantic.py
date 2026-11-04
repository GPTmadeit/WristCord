"""Extract Discord semantic color tokens (per theme) from the native generated-definitions module."""
import io, json, os, sys
from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from hermes_dec.parsers.serialized_literal_parser import unpack_slp_array

B = os.environ.get('HQ_BUNDLE', os.path.expanduser('~/re/discord-android-351.11/apk/assets/index.android.bundle'))
OUT = os.environ.get('HQ_OUT', os.path.expanduser('~/re/discord-android-351.11/out'))
data = open(B, 'rb').read()
r = HBCReader(); r.read_whole_file(io.BufferedReader(io.BytesIO(data)))

def obj(ins):
    if ins.inst.name == 'NewObjectWithBuffer' or ins.inst.name == 'NewObjectWithBufferLong':
        keys = r.object_shape_keys[ins.arg2]
        vals = unpack_slp_array(r.literal_values[ins.arg3:], len(keys), r.header.version)
        out = {}
        for k, it in zip(keys, vals.items):
            k = k.strip("'\"")
            v = it.value
            if it.tag_type.name.endswith('StringTag'):
                v = r.strings[v]
            out[k] = v
        return out
    return None

fid = int(sys.argv[1]) if len(sys.argv) > 1 else 590
regs, names, tokens = {}, None, {}
for ins in parse_hbc_bytecode(r.function_headers[fid], r):
    n = ins.inst.name
    if n.startswith('NewObjectWithBuffer'):
        o = obj(ins)
        regs[ins.arg1] = o
        if o is not None and len(o) > 50:
            names = list(o.keys())
    elif n in ('GetById', 'GetByIdShort', 'GetByIdLong'):
        regs[ins.arg1] = ('id', r.strings[ins.arg4])
    elif n == 'DefineOwnByVal':
        target, val, key = regs.get(ins.arg1), regs.get(ins.arg2), regs.get(ins.arg3)
        if isinstance(target, dict) and isinstance(key, tuple):
            target[key[1]] = val
    elif n in ('PutOwnBySlotIdx', 'PutOwnBySlotIdxLong'):
        val = regs.get(ins.arg2)
        if names and isinstance(val, dict) and 'category' in val:
            tokens[names[ins.arg3]] = val
json.dump(tokens, open(OUT + '/semantic-colors.json', 'w'), indent=1)
print(len(tokens), 'semantic tokens')
