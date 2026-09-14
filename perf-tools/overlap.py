import re, sys, collections

path = sys.argv[1]
pid = sys.argv[2]
line_re = re.compile(r"^\s*(\S+)-(\d+)\s+\(\s*(\d+|-+)\)\s+\[\d+\]\s+\S+\s+(\d+\.\d+): tracing_mark_write: (.*)$")

stacks = collections.defaultdict(list)
anims, decodes = [], []
for line in open(path, errors="ignore"):
    m = line_re.match(line)
    if not m:
        continue
    tname, tid, tgid, ts, payload = m.groups()
    ts = float(ts); tid = int(tid)
    if payload.startswith("B|"):
        p = payload.split("|", 2)
        stacks[tid].append((p[2] if len(p) > 2 else "", ts))
    elif payload.startswith("E") and stacks[tid]:
        name, start = stacks[tid].pop()
        if tgid != pid:
            continue
        dur = (ts - start) * 1000.0
        if name == "animation" and dur > 15:
            anims.append((start, ts, dur))
        if name.startswith("Decoding") and dur > 15:
            decodes.append((start, ts, dur, name, tname))

t0 = min([a[0] for a in anims] + [d[0] for d in decodes])
print("big animation slices (UI thread) and decode overlap:")
for s, e, d in sorted(anims):
    ov = [(dd, nn, tn) for (ds, de, dd, nn, tn) in decodes if ds < e and de > s]
    tot = sum(x[0] for x in ov)
    print("  anim %7.2f ms at +%6.3fs  | %d decodes overlapping, %7.1f ms of decode CPU" % (d, s - t0, len(ov), tot))
    for dd, nn, tn in sorted(ov, reverse=True)[:4]:
        print("        %8.1f ms  %-28s %s" % (dd, nn, tn))

print()
print("all decodes > 15 ms:")
for s, e, d, n, tn in sorted(decodes, key=lambda x: -x[2]):
    print("  %8.1f ms  %-28s at +%6.3fs  %s" % (d, n, s - t0, tn))
