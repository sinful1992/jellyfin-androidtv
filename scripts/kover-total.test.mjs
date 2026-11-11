// node --test scripts/kover-total.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
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
