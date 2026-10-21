# Code Port - Progress Report

**Generated:** 2026-10-21
**Source:** tmp/android_system_properties/src
**Target:** src

## Executive Summary

| Metric | Count | Percentage |
|--------|-------|------------|
| Function parity | 0/0 matched | N/A |
| Class/type parity | 0/0 matched | N/A |
| Combined symbol parity | 0/0 matched | N/A |
| Average function body similarity | N/A (no matched function-bearing files) | inline-code cosine |
| Average documentation similarity | 0.00 | doc text cosine |
| Missing source functions | 0 | 0% parity until ported |
| Missing source classes/types | 0 | 0% parity until ported |
| Missing source symbol files | 0 | 0 symbols |
| Cheat/scoring failures | 0 | forced to 0% |
| Total source files | 1 | 100% |
| Target units (paired) | 7 | - |
| Target files (total) | 7 | - |
| Porting progress | 0 | 0.0% (matched) |
| Missing files | 0 | 0.0% |
| Reexport/wiring files | 1 | consult-only |

**Report warning:** no source/target files were matched. Check the source and target roots or missing `port-lint` provenance before trusting this run.

## Port Quality Analysis

**Average Function Similarity:** N/A (no matched function-bearing files)

Similarity in this report is the required function-by-function body/parameter score. Class/type parity and symbol deficits are reported beside it; whole-file shape is diagnostic only.

**Work Distribution:**
- Critical (<0.60): 0 files (0.0% of matched)
- Needs review (0.60-0.84): 0 files (0.0% of matched)
- Excellent (>=0.85): 0 files (0.0% of matched)

## Worst Function Scores First

Every matched file is listed from lowest function body/parameter similarity upward. Missing symbol names are not capped.

| Rank | Source | Target | Function similarity | Functions | Missing functions | Types | Missing types | Tests | Symbol deficit | Priority |
|------|--------|--------|---------------------|-----------|-------------------|-------|---------------|-------|----------------|----------|

## Cheat Detection / Scoring Failures

_None detected._

### Critical Ports (Similarity < 0.60, Worst First)

These files need significant work:


## Incorrect Ports (Missing Types)

These files are matched (often via `// port-lint`) but appear to be missing one or more type declarations
present in the Rust source file.

| Source | Target | Missing types | Examples |
|--------|--------|---------------|----------|
| _None detected_ | | | |

## High Priority Missing Files

No missing files detected.

## Documentation Gaps

**Documentation line amount:** 0 / 0 lines (N/A)

Documentation gaps (>20%), complete list:

No significant documentation line-count gaps found; text/reference correspondence is separate.

## Reexport / Wiring Modules

These files match `reexport_modules` patterns in `.ast_distance_config.json`. They are filtered out of
normal priority and missing-file ladders because they are wiring
modules, not direct logic ports. Consult them for call-site routing;
do not treat them as the next implementation target by default.

### Missing

| Source | Expected target | Deps | Source path | Expected path |
|--------|-----------------|------|-------------|---------------|
| `lib` | `Lib` | 0 | `lib.rs` | `Lib.kt` |

