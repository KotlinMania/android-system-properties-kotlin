# Code Port - Progress Report

**Generated:** 2026-06-03
**Source:** tmp/android_system_properties/src
**Target:** src

## Executive Summary

| Metric | Count | Percentage |
|--------|-------|------------|
| Function parity | 3/6 matched (target 7) | 50.0% |
| Class/type parity | 5/5 matched (target 6) | 100.0% |
| Combined symbol parity | 8/11 matched (target 13) | 72.7% |
| Average function body similarity | 0.20 | inline-code cosine |
| Average documentation similarity | 0.00 | doc text cosine |
| Missing source functions | 0 | 0% parity until ported |
| Missing source classes/types | 0 | 0% parity until ported |
| Missing source symbol files | 0 | 0 symbols |
| Cheat/scoring failures | 0 | forced to 0% |
| Total source files | 1 | 100% |
| Target units (paired) | 11 | - |
| Target files (total) | 11 | - |
| Porting progress | 1 | 100.0% (matched) |
| Missing files | 0 | 0.0% |

## Port Quality Analysis

**Average Function Similarity:** 0.20

Similarity in this report is the required function-by-function body/parameter score. Class/type parity and symbol deficits are reported beside it; whole-file shape is diagnostic only.

**Work Distribution:**
- Critical (<0.60): 1 files (100.0% of matched)
- Needs review (0.60-0.84): 0 files (0.0% of matched)

## Worst Function Scores First

Every matched file is listed from lowest function body/parameter similarity upward. Missing symbol names are not capped.

| Rank | Source | Target | Function similarity | Functions | Missing functions | Types | Missing types | Tests | Symbol deficit | Priority |
|------|--------|--------|---------------------|-----------|-------------------|-------|---------------|-------|----------------|----------|
| 1 | `lib` | `jvmMain.kotlin.io.github.kotlinmania.androidsystemproperties.Lib` | 0.20 | 3/6 matched (target 7) | `load_fn`, `get_from_cstr`, `drop` | 5/5 matched (target 6) | _none_ | - | 3 | 31108.0 |

## Cheat Detection / Scoring Failures

_None detected._

### Critical Ports (Similarity < 0.60, Worst First)

These files need significant work:

- `lib` -> `jvmMain.kotlin.io.github.kotlinmania.androidsystemproperties.Lib` (0.20)

## Incorrect Ports (Missing Types)

These files are matched (often via `// port-lint`) but appear to be missing one or more type declarations
present in the Rust source file.

| Source | Target | Missing types | Examples |
|--------|--------|---------------|----------|
| _None detected_ | | | |

## High Priority Missing Files

No missing files detected.

## Documentation Gaps

There is missing documentation that is hurting overall scoring.

**Documentation coverage:** 0 / 164 lines (0%)

Documentation gaps (>20%), complete list:

- `lib` - 100% gap (164 → 0 lines)

