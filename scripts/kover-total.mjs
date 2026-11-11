#!/usr/bin/env node
// Sums Kover (JaCoCo-format) XML reports: node scripts/kover-total.mjs [rootDir]
// Prints one line per module and the total line coverage. Exit 1 when no report is found.
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';

export function reportTotals(xml) {
	// The report-level counters are the <counter> elements after the last </package>.
	const tail = xml.slice(xml.lastIndexOf('</package>') + 1);
	const out = {};
	for (const m of tail.matchAll(/<counter type="(\w+)" missed="(\d+)" covered="(\d+)"\/>/g)) {
		out[m[1]] = { missed: Number(m[2]), covered: Number(m[3]) };
	}
	return out;
}

export function findReports(dir, name = 'reportDebug.xml', found = []) {
	for (const e of readdirSync(dir)) {
		if (e === 'node_modules' || e === '.git' || e === '.gradle') continue;
		const p = join(dir, e);
		if (!statSync(p).isDirectory()) continue;
		const r = join(p, 'build', 'reports', 'kover', name);
		if (existsSync(r)) found.push(r);
		findReports(p, name, found);
	}
	return found;
}

export const pct = (c) => (c.covered + c.missed === 0 ? 0 : (100 * c.covered) / (c.covered + c.missed));

if (process.argv[1]?.endsWith('kover-total.mjs')) {
	const root = process.argv[2] ?? '.';
	const reports = findReports(root);
	if (!reports.length) { console.error('no build/reports/kover/reportDebug.xml found'); process.exit(1); }
	const total = { missed: 0, covered: 0 };
	for (const r of reports) {
		const line = reportTotals(readFileSync(r, 'utf8')).LINE ?? { missed: 0, covered: 0 };
		total.missed += line.missed; total.covered += line.covered;
		console.log(`${relative(root, r).split(/[\\/]build[\\/]/)[0].padEnd(32)} ${pct(line).toFixed(2).padStart(6)} %  (${line.covered}/${line.covered + line.missed} lines)`);
	}
	console.log(`${'TOTAL'.padEnd(32)} ${pct(total).toFixed(2).padStart(6)} %  (${total.covered}/${total.covered + total.missed} lines)`);
}
