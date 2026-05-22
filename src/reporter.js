import chalk from 'chalk';

const SEVERITY_ICON  = { critical: '❌', major: '❌', warning: '⚠️ ', info: 'ℹ️ ' };
const SEVERITY_ORDER = { critical: 0, major: 1, warning: 2, info: 3 };

function label(severity) {
  switch (severity) {
    case 'critical': return chalk.red.bold('CRITICAL');
    case 'major':    return chalk.yellow.bold('MAJOR');
    case 'warning':  return chalk.yellow('WARNING');
    default:         return chalk.cyan('INFO');
  }
}

export function printHeader(projectPath, fileCount) {
  console.log('');
  console.log(chalk.bold.blue('java-vibe-guard') + chalk.gray(' — vibe coding detector for Java/Spring Boot'));
  console.log(chalk.gray(`Scanning: ${projectPath}  (${fileCount} files)\n`));
}

export function printFindings(findings) {
  if (findings.length === 0) {
    console.log(chalk.bold.green('✅ No vibe coding patterns detected. Looks production-ready!'));
    return;
  }
  const sorted = [...findings].sort(
    (a, b) => (SEVERITY_ORDER[a.severity] ?? 4) - (SEVERITY_ORDER[b.severity] ?? 4)
  );
  for (const f of sorted) {
    const icon = SEVERITY_ICON[f.severity] ?? 'ℹ️ ';
    console.log(`${icon} ${label(f.severity)}: ${f.message} → ${chalk.cyan(f.location)}`);
  }
}

export function printSummary(findings) {
  const c = { critical: 0, major: 0, warning: 0, info: 0 };
  for (const f of findings) c[f.severity] = (c[f.severity] || 0) + 1;

  console.log('');
  console.log(chalk.gray('─'.repeat(62)));

  const parts = [];
  if (c.critical > 0) parts.push(chalk.red.bold(`${c.critical} critical`));
  if (c.major > 0)    parts.push(chalk.yellow(`${c.major} major`));
  if (c.warning > 0)  parts.push(chalk.yellow(`${c.warning} warnings`));
  if (c.info > 0)     parts.push(chalk.cyan(`${c.info} info`));
  if (!parts.length)  parts.push(chalk.green('0 issues'));

  console.log(`📊 Summary: ${parts.join(' · ')}`);
  console.log(chalk.gray('─'.repeat(62)));
  console.log('');

  if (c.critical === 0 && c.major === 0 && c.warning === 0) {
    console.log(chalk.bold.green('✅ Production-ready! No vibe coding patterns found.'));
  } else if (c.critical > 0) {
    console.log(chalk.bold.red(`🚨 ${c.critical} CRITICAL issue(s) found — fix before deploying to production.`));
  } else {
    console.log(chalk.bold.yellow('⚠️  No critical issues — review warnings before deploying.'));
  }
  console.log('');
}

export function printJSON(findings, projectPath, fileCount) {
  const c = { critical: 0, major: 0, warning: 0, info: 0 };
  for (const f of findings) c[f.severity] = (c[f.severity] || 0) + 1;
  console.log(JSON.stringify({
    timestamp: new Date().toISOString(),
    projectPath,
    filesScanned: fileCount,
    summary: c,
    healthy: c.critical === 0,
    findings: findings.map(f => ({
      severity: f.severity,
      rule: f.rule,
      message: f.message,
      location: f.location,
    })),
  }, null, 2));
}
