#!/usr/bin/env python3
"""hq - query a Hermes bytecode bundle by string -> function -> module.

    hq.py index                     build the cross-reference index (once)
    hq.py str   <regex>             string ids/values matching regex
    hq.py refs  <regex> [max]       functions referencing matching strings
    hq.py dis   <fid>               disassemble one function
    hq.py dec   <fid>               decompile one function (+ nested closures)
    hq.py mod   <fid>               decompile the Metro module containing fid
    hq.py up    <fid>               closure ancestry of fid
    hq.py modid <moduleId>          decompile Metro module by id
    hq.py deps  <fid>               dependency module ids of fid's module
    hq.py files <regex>             Metro modules by original source path
    hq.py where <fid>               original source path of fid's module
    hq.py fn    <regex>             functions by name

Never executes the bundle; it only parses it.
"""
import io
import os
import pickle
import re
import sys
from multiprocessing import Pool

sys.setrecursionlimit(100000)

from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from hermes_dec.parsers.hbc_opcodes.def_classes import OperandMeaning
from hermes_dec.parsers.serialized_literal_parser import unpack_slp_array, TagType

BUNDLE = os.environ.get(
    "HQ_BUNDLE",
    os.path.expanduser("~/re/discord-android-351.11/apk/assets/index.android.bundle"),
)
IDX = os.environ.get("HQ_INDEX", os.path.expanduser("~/re/discord-android-351.11/out/hq.idx"))

STRING_TAGS = (TagType.LongStringTag, TagType.ShortStringTag, TagType.ByteStringTag)

_reader = None


def reader():
    global _reader
    if _reader is None:
        data = open(BUNDLE, "rb").read()
        r = HBCReader()
        r.read_whole_file(io.BufferedReader(io.BytesIO(data)))
        r._data = data
        _reader = r
    return _reader


def _literal_string_ids(r, ins):
    out = []
    name = ins.inst.name
    try:
        if name in ("NewArrayWithBuffer", "NewArrayWithBufferLong"):
            arr = unpack_slp_array(r.literal_values[ins.arg4:], ins.arg3, r.header.version)
            out += [it.value for it in arr.items if it.tag_type in STRING_TAGS]
        elif name in ("NewObjectWithBuffer", "NewObjectWithBufferLong"):
            n = len(r.object_shape_keys[ins.arg2])
            arr = unpack_slp_array(r.literal_values[ins.arg3:], n, r.header.version)
            out += [it.value for it in arr.items if it.tag_type in STRING_TAGS]
    except Exception:
        pass
    return out


def _scan(rng):
    r = reader()
    # Each worker needs its own file position.
    r.file_buffer = io.BufferedReader(io.BytesIO(r._data))
    res = []
    for fid in range(*rng):
        fh = r.function_headers[fid]
        strs, kids, shapes = set(), [], set()
        try:
            for ins in parse_hbc_bytecode(fh, r):
                for i, op in enumerate(ins.inst.operands):
                    m = op.operand_meaning
                    if m == OperandMeaning.string_id:
                        strs.add(getattr(ins, "arg%d" % (i + 1)))
                    elif m == OperandMeaning.function_id:
                        kids.append(getattr(ins, "arg%d" % (i + 1)))
                if ins.inst.name.startswith("New") and "WithBuffer" in ins.inst.name:
                    strs.update(_literal_string_ids(r, ins))
                    if "Object" in ins.inst.name:
                        shapes.add(ins.arg2)
        except Exception as e:  # noqa: BLE001 - keep indexing other functions
            strs.add(-1)
        res.append((fid, sorted(strs), kids, sorted(shapes)))
    return res


def build_index():
    r = reader()
    n = len(r.function_headers)
    chunk = 2000
    ranges = [(a, min(a + chunk, n)) for a in range(0, n, chunk)]
    with Pool(4) as pool:
        parts = pool.map(_scan, ranges)
    fstrs, fkids, fshapes = [None] * n, [None] * n, [None] * n
    for part in parts:
        for fid, s, k, sh in part:
            fstrs[fid], fkids[fid], fshapes[fid] = s, k, sh
    parent = [-1] * n
    for fid, ks in enumerate(fkids):
        for k in ks:
            if parent[k] == -1 and k != fid:
                parent[k] = fid
    # Shape keys are identifiers; map them back to string ids for searching.
    sid = {s: i for i, s in enumerate(r.strings)}
    for fid in range(n):
        extra = set()
        for sh in fshapes[fid]:
            for key in r.object_shape_keys[sh]:
                k = key[1:-1] if key[:1] in "'\"" else key
                if k in sid:
                    extra.add(sid[k])
        if extra:
            fstrs[fid] = sorted(set(fstrs[fid]) | extra)
    s2f = {}
    for fid, ss in enumerate(fstrs):
        for s in ss:
            s2f.setdefault(s, []).append(fid)
    modules = module_ids(r)
    idx = dict(
        names=[r.function_headers[i].functionName for i in range(n)],
        sizes=[r.function_headers[i].bytecodeSizeInBytes for i in range(n)],
        fstrs=fstrs, fkids=fkids, parent=parent, s2f=s2f,
        strings=r.strings, modules=modules,
    )
    pickle.dump(idx, open(IDX, "wb"), protocol=pickle.HIGHEST_PROTOCOL)
    print("indexed", n, "functions;", len(modules), "metro modules")


def module_ids(r):
    """Map module-factory fid -> (module id, dependency ids) from the global __d() calls."""
    out = {}
    g = r.function_headers[r.header.globalCodeIndex]
    pending = None
    consts = {}
    deps = {}
    for ins in parse_hbc_bytecode(g, r):
        nm = ins.inst.name
        if nm in ("CreateClosure", "CreateClosureLongIndex"):
            pending = (ins.arg1, ins.arg3)
        elif nm.startswith("LoadConst") and nm not in ("LoadConstString", "LoadConstStringLongIndex"):
            val = getattr(ins, "arg2", None)
            if nm == "LoadConstZero":
                val = 0
            consts[ins.arg1] = val
        elif nm in ("NewArrayWithBuffer", "NewArrayWithBufferLong"):
            try:
                arr = unpack_slp_array(r.literal_values[ins.arg4:], ins.arg3, r.header.version)
                deps[ins.arg1] = [it.value for it in arr.items]
            except Exception:
                deps[ins.arg1] = None
        elif nm.startswith("Call") and pending is not None:
            # __d(factory, moduleId, deps): args are consecutive registers
            regs = [getattr(ins, a) for a in ("arg3", "arg4", "arg5", "arg6") if hasattr(ins, a)]
            fac_reg, fid = pending
            mid, dep = None, None
            for reg in regs:
                if reg in consts:
                    mid = consts[reg] if mid is None else mid
                if reg in deps:
                    dep = deps[reg]
            if mid is None:
                # Call with argument registers laid out below: look at consts table near fac_reg
                mid = consts.get(fac_reg - 1) if consts.get(fac_reg - 1) is not None else consts.get(fac_reg + 1)
            out[fid] = (mid, dep)
            pending = None
            consts.clear()
            deps.clear()
    return out


def load_idx():
    return pickle.load(open(IDX, "rb"))


def fname(idx, fid):
    return idx["strings"][idx["names"][fid]]


def module_of(idx, fid):
    chain = [fid]
    while idx["parent"][chain[-1]] not in (-1, 0):
        chain.append(idx["parent"][chain[-1]])
    return chain[-1], chain


def cmd_str(pat):
    idx = load_idx()
    rx = re.compile(pat)
    for i, s in enumerate(idx["strings"]):
        if rx.search(s):
            print(i, repr(s)[:300], "refs=%d" % len(idx["s2f"].get(i, [])))


def cmd_refs(pat, maxn=60):
    idx = load_idx()
    rx = re.compile(pat)
    hits = [i for i, s in enumerate(idx["strings"]) if rx.search(s)]
    for i in hits[:200]:
        fids = idx["s2f"].get(i, [])
        print("== string %d %r: %d functions" % (i, idx["strings"][i][:160], len(fids)))
        for fid in fids[:maxn]:
            mod, chain = module_of(idx, fid)
            mid = idx["modules"].get(mod, (None, None))[0]
            print("   fid %-7d %-40s %6dB  module fid %d (id %s) depth %d" % (
                fid, fname(idx, fid)[:40], idx["sizes"][fid], mod, mid, len(chain)))


def decompile(fid):
    from hermes_dec.decompilation.defs import HermesDecompiler
    from hermes_dec.decompilation.hbc_decompiler import decompile_function
    st = HermesDecompiler()
    st.hbc_reader = reader()
    st.calldirect_function_ids = set()
    st.indent_level = 0
    decompile_function(st, fid)


def cmd_dis(fid):
    from hermes_dec.disassembly.hbc_disassembler import disassemble_function
    r = reader()
    disassemble_function(r, fid, r.function_headers[fid])


def cmd_mod(fid):
    idx = load_idx()
    mod, chain = module_of(idx, fid)
    print("// module factory fid %d, metro id %s, chain %s" % (mod, idx["modules"].get(mod), chain))
    decompile(mod)


def cmd_modid(mid):
    idx = load_idx()
    for fid, (m, _d) in idx["modules"].items():
        if m == mid:
            print("// module factory fid %d, metro id %s" % (fid, m))
            decompile(fid)
            return
    print("no module", mid)


def cmd_up(fid):
    idx = load_idx()
    mod, chain = module_of(idx, fid)
    for f in chain:
        print(f, fname(idx, f), idx["sizes"][f])
    print("module", idx["modules"].get(mod))


def cmd_deps(fid):
    idx = load_idx()
    mod, _ = module_of(idx, fid)
    print(idx["modules"].get(mod))


def module_paths(idx):
    rx = re.compile(r"^[\w@.\-]+(/[\w@.\-\[\]]+)+\.(tsx|ts|jsx|js|mjs|cjs)$")
    out = {}
    for fid in idx["modules"]:
        for s in idx["fstrs"][fid]:
            if s >= 0 and rx.match(idx["strings"][s]):
                out[fid] = idx["strings"][s]
                break
    return out


def cmd_files(pat):
    idx = load_idx()
    paths = module_paths(idx)
    rx = re.compile(pat)
    for fid, p in sorted(paths.items(), key=lambda kv: kv[1]):
        if rx.search(p):
            print("fid %-7d id %-6s %s" % (fid, idx["modules"][fid][0], p))


def cmd_where(fid):
    idx = load_idx()
    mod, _ = module_of(idx, fid)
    print(module_paths(idx).get(mod), idx["modules"].get(mod))



def cmd_fn(pat):
    idx = load_idx()
    rx = re.compile(pat)
    paths = module_paths(idx)
    for fid, nid in enumerate(idx["names"]):
        nm = idx["strings"][nid]
        if nm and rx.search(nm):
            mod, chain = module_of(idx, fid)
            print("fid %-7d %-40s %6dB  %s" % (fid, nm[:40], idx["sizes"][fid], paths.get(mod)))


if __name__ == "__main__":
    cmd, *args = sys.argv[1:] or ["help"]
    if cmd == "index":
        build_index()
    elif cmd == "str":
        cmd_str(args[0])
    elif cmd == "refs":
        cmd_refs(args[0], int(args[1]) if len(args) > 1 else 60)
    elif cmd == "dis":
        cmd_dis(int(args[0]))
    elif cmd == "dec":
        decompile(int(args[0]))
    elif cmd == "mod":
        cmd_mod(int(args[0]))
    elif cmd == "modid":
        cmd_modid(int(args[0]))
    elif cmd == "up":
        cmd_up(int(args[0]))
    elif cmd == "deps":
        cmd_deps(int(args[0]))
    elif cmd == "files":
        cmd_files(args[0])
    elif cmd == "where":
        cmd_where(int(args[0]))
    elif cmd == "fn":
        cmd_fn(args[0])
    else:
        print(__doc__)
