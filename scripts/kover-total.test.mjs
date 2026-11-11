// node --test scripts/kover-total.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { reportTotals, findReports, pct } from './kover-total.mjs';

const xml = (pkgLine, total) => `<report name="x"><package name="a"><class name="a/B"><counter type="LINE" missed="${pkgLine[0]}" covered="${pkgLine[1]}"/></class><counter type="LINE" missed="${pkgLine[0]}" covered="${pkgLine[1]}"/></package><counter type="INSTRUCTION" missed="9" covered="1"/><counter type="LINE" missed="${total[0]}" covered="${total[1]}"/></report>`;

test('reads the report-level counter, not the package one', () => {
	const t = reportTotals(xml([5, 5], [30, 70]));
	assert.deepEqual(t.LINE, { missed: 30, covered: 70 });
	assert.deepEqual(t.INSTRUCTION, { missed: 9, covered: 1 });
});
test('empty report gives no counters, pct of zero lines is 0', () => {
	assert.deepEqual(reportTotals('<report name="x"></report>'), {});
	assert.equal(pct({ missed: 0, covered: 0 }), 0);
	assert.equal(pct({ missed: 1, covered: 3 }), 75);
});
test('finds nested module reports and skips .gradle', () => {
	const d = mkdtempSync(join(tmpdir(), 'kover-'));
	for (const m of ['app', 'playback/core', '.gradle/x']) {
		mkdirSync(join(d, m, 'build/reports/kover'), { recursive: true });
		writeFileSync(join(d, m, 'build/reports/kover/reportDebug.xml'), xml([1, 1], [1, 1]));
	}
	const found = findReports(d).map((p) => p.replace(d, '').replace(/\\/g, '/'));
	assert.deepEqual(found.sort(), ['/app/build/reports/kover/reportDebug.xml', '/playback/core/build/reports/kover/reportDebug.xml']);
});

// task 145 (ada break case): a cut-off or empty report used to count as 0/0 lines and the run still exited 0
test('a report without a LINE counter fails the run instead of counting as 0/0', () => {
	const script = join(dirname(fileURLToPath(import.meta.url)), 'kover-total.mjs');
	const run = (body) => {
		const d = mkdtempSync(join(tmpdir(), 'kover-bad-'));
		mkdirSync(join(d, 'm', 'build', 'reports', 'kover'), { recursive: true });
		writeFileSync(join(d, 'm', 'build', 'reports', 'kover', 'reportDebug.xml'), body);
		return spawnSync(process.execPath, [script, d], { encoding: 'utf8' });
	};
	for (const body of ['garbage', '', '<report name="x"><counter type="INSTRUCTION" missed="1" covered="1"/></report>']) {
		const r = run(body);
		assert.equal(r.status, 1, `exit for ${JSON.stringify(body)}`);
		assert.match(r.stderr, /no LINE counter/);
	}
	const ok = run(xml([1, 1], [3, 7]));
	assert.equal(ok.status, 0);
	assert.match(ok.stdout, /TOTAL\s+70\.00 %/);
});

// linus #10664: a report cut off inside a package kept that package's class/method counters as the "total"
test('a report cut off inside a package fails the run instead of printing the partial package as the total', () => {
	const script = join(dirname(fileURLToPath(import.meta.url)), 'kover-total.mjs');
	const whole = '<report name="x"><package name="a"><class name="a/B"><counter type="LINE" missed="5" covered="0"/></class><counter type="LINE" missed="5" covered="0"/></package>';
	const cut = '<package name="b"><class name="b/C"><method name="m"><counter type="LINE" missed="0" covered="3"/>';
	for (const body of [whole + cut, whole + cut + '</report>', whole]) {
		const d = mkdtempSync(join(tmpdir(), 'kover-cut-'));
		mkdirSync(join(d, 'm', 'build', 'reports', 'kover'), { recursive: true });
		writeFileSync(join(d, 'm', 'build', 'reports', 'kover', 'reportDebug.xml'), body);
		const r = spawnSync(process.execPath, [script, d], { encoding: 'utf8' });
		assert.equal(r.status, 1, `exit for ${body.slice(-60)}`);
		assert.doesNotMatch(r.stdout, /TOTAL/);
	}
	assert.deepEqual(reportTotals(whole + cut + '</report>'), {});
	assert.deepEqual(reportTotals(xml([1, 1], [3, 7]) + '\n'), { INSTRUCTION: { missed: 9, covered: 1 }, LINE: { missed: 3, covered: 7 } });
});
