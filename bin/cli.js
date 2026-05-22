#!/usr/bin/env node
import { program } from 'commander';
import { readFileSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';
import { runGuard } from '../src/scanner.js';

const __dirname = dirname(fileURLToPath(import.meta.url));
const pkg = JSON.parse(readFileSync(join(__dirname, '../package.json'), 'utf8'));

program
  .name('java-vibe-guard')
  .description('Static analyzer that detects vibe coding patterns in Java/Spring Boot projects')
  .version(pkg.version)
  .argument('<path>', 'Path to Java/Spring Boot project to analyze')
  .option('--json', 'Output results as JSON')
  .option('--rule <name>', 'Run only one rule: blocking | layers | kafka | transactions | observability')
  .option('--no-color', 'Disable colored output')
  .parse();

const opts = program.opts();
const projectPath = program.args[0];

if (!projectPath) {
  console.error('Error: path argument is required');
  process.exit(1);
}

process.exitCode = await runGuard(projectPath, opts);
