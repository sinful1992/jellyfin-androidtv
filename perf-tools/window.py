import re, sys, collections

path, tid_want, t0, t1 = sys.argv[1], int(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4])
line_re = re.compile(r"^\s*(\S+)-(\d+)\s+\(\s*(\d+|-+)\)\s+\[\d+\]\s+\S+\s+(\d+\.\d+): tracing_mark_write: (.*)$")

stacks = collections.defaultdict(list)
out = []
for line in open(path, errors="ignore"):
    m = line_re.match(line)
    if not m:
        continue
    tname, tid, tgid, ts, payload = m.groups()
    ts = float(ts); tid = int(tid)
    if payload.startswith("B|"):
        parts = payload.split("|", 2)
        name = parts[2] if len(parts) > 2 else ""
        stacks[tid].append((name, ts, len(stacks[tid])))
    elif payload.startswith("E"):
        if stacks[tid]:
            name, start, depth = stacks[tid].pop()
            if tid == tid_want and start >= t0 and start <= t1:
                out.append((start, depth, (ts - start) * 1000.0, name))

out.sort()
for start, depth, dur, name in out:
    print("%.5f %s%-52s %8.3f ms" % (start, "  " * depth, name[:52], dur))
