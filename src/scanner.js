import { readdirSync, statSync, readFileSync } from 'fs';
import { join, extname, relative, resolve } from 'path';
import { checkBlocking } from './rules/blocking.js';
import { checkLayers } from './rules/layers.js';
import { checkKafka } from './rules/kafka.js';
import { checkTransactions } from './rules/transactions.js';
import { checkObservability } from './rules/observability.js';
import { printHeader, printFindings, printSummary, printJSON } from './reporter.js';

const RULES = [
  { id: 'blocking',      fn: checkBlocking },
  { id: 'layers',        fn: checkLayers },
  { id: 'kafka',         fn: checkKafka },
  { id: 'transactions',  fn: checkTransactions },
  { id: 'observability', fn: checkObservability },
];

const IGNORED_DIRS = new Set([
  'node_modules', '.git', 'target', 'build', '.gradle', '.mvn',
  'out', 'dist', '.idea', '__pycache__', 'benchmark',
]);

const SCAN_EXTENSIONS = new Set(['.java', '.yml', '.yaml', '.properties', '.xml', '.gradle', '.kts']);

export function collectFiles(dir, files = []) {
  let entries;
  try { entries = readdirSync(dir); } catch { return files; }
  for (const entry of entries) {
    if (IGNORED_DIRS.has(entry)) continue;
    const fullPath = join(dir, entry);
    let stat;
    try { stat = statSync(fullPath); } catch { continue; }
    if (stat.isDirectory()) {
      collectFiles(fullPath, files);
    } else if (SCAN_EXTENSIONS.has(extname(entry))) {
      files.push(fullPath);
    }
  }
  return files;
}

export async function runGuard(projectPath, opts = {}) {
  const absPath = resolve(projectPath);
  const only = opts.rule ? opts.rule.toLowerCase() : null;
  const rules = only ? RULES.filter(r => r.id === only) : RULES;

  if (only && rules.length === 0) {
    console.error(`Unknown rule: "${only}". Valid: ${RULES.map(r => r.id).join(', ')}`);
    return 1;
  }

  const files = collectFiles(absPath);
  if (files.length === 0) {
    console.error(`No Java/config files found in: ${absPath}`);
    return 1;
  }

  const fileContexts = files.map(filePath => {
    let lines = [];
    try { lines = readFileSync(filePath, 'utf8').split('\n'); } catch {}
    return {
      filePath,
      lines,
      relativePath: relative(absPath, filePath),
      fileName: filePath.split('/').pop(),
    };
  });

  const allFindings = [];
  for (const rule of rules) {
    try {
      const findings = rule.fn(fileContexts);
      allFindings.push(...findings);
    } catch (e) {
      if (!opts.json) console.error(`Rule ${rule.id} error: ${e.message}`);
    }
  }

  if (opts.json) {
    printJSON(allFindings, projectPath, files.length);
  } else {
    printHeader(projectPath, files.length);
    printFindings(allFindings);
    printSummary(allFindings);
  }

  return allFindings.some(f => f.severity === 'critical') ? 1 : 0;
}
