import re, sys, collections

path = sys.argv[1]
target_pkg_pid = sys.argv[2] if len(sys.argv) > 2 else None

line_re = re.compile(r"^\s*(\S+)-(\d+)\s+\(\s*(\d+|-+)\)\s+\[\d+\]\s+\S+\s+(\d+\.\d+): tracing_mark_write: (.*)$")

stacks = collections.defaultdict(list)          # tid -> [(name, ts)]
agg = collections.defaultdict(lambda: [0, 0.0, 0.0])  # name -> [count, total_ms, max_ms]
slices = []                                     # (dur_ms, name, tid, tname, ts)
tid_name = {}
tid_tgid = {}

for line in open(path, errors="ignore"):
    m = line_re.match(line)
    if not m:
        continue
    tname, tid, tgid, ts, payload = m.groups()
    ts = float(ts)
    tid = int(tid)
    tid_name[tid] = tname
    tid_tgid[tid] = tgid
    if payload.startswith("B|"):
        parts = payload.split("|", 2)
        name = parts[2] if len(parts) > 2 else ""
        stacks[tid].append((name, ts))
    elif payload.startswith("E"):
        if stacks[tid]:
            name, start = stacks[tid].pop()
            dur = (ts - start) * 1000.0
            if target_pkg_pid and tid_tgid[tid] != target_pkg_pid:
                continue
            a = agg[name]
            a[0] += 1
            a[1] += dur
            if dur > a[2]:
                a[2] = dur
            slices.append((dur, name, tid, tname, start))

print("=== top slice names by total time (pid filter=%s) ===" % target_pkg_pid)
print("%-52s %6s %10s %10s" % ("name", "count", "total_ms", "max_ms"))
for name, (c, tot, mx) in sorted(agg.items(), key=lambda kv: -kv[1][1])[:35]:
    print("%-52s %6d %10.1f %10.1f" % (name[:52], c, tot, mx))

print()
print("=== longest individual slices ===")
for dur, name, tid, tname, start in sorted(slices, reverse=True)[:30]:
    print("%8.2f ms  %-44s  tid=%-7d %-16s @%.4f" % (dur, name[:44], tid, tname, start))
