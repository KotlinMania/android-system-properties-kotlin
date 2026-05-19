# Immediate Actions - High-Value Files

Based on AST analysis, here are the concrete next steps.

## Summary

- **Files Present:** 1/1 (100.0%)
- **Function parity:** 3/6 matched (target 6) — 50.0%
- **Class/type parity:** 3/5 matched (target 4) — 60.0%
- **Combined symbol parity:** 6/11 matched (target 10) — 54.5%
- **Average inline-code cosine:** 0.20 (function body across 1 matched files)
- **Average documentation cosine:** 0.00 (doc text across 1 matched files)
- **Cheat-zeroed Files:** 0
- **Critical Issues:** 1 files with <0.60 function similarity

## Priority 1: Fix Incomplete High-Dependency Files

No incomplete high-dependency files detected.

## Priority 2: Port Missing High-Value Files

Critical missing files (>10 dependencies):

No missing high-value files detected.

## Detailed Work Items

Every matched file is listed below with function and type symbol parity.

### 1. lib

- **Target:** `androidsystemproperties.Lib`
- **Similarity:** 0.20
- **Dependents:** 0
- **Priority Score:** 51108.0
- **Functions:** 3/6 matched
- **Missing functions:** `load_fn`, `get_from_cstr`, `drop`
- **Types:** 3/5 matched (target 4)
- **Missing types:** `SystemPropertyFindFn`, `SystemPropertyReadCallbackFn`

## Success Criteria

For each file to be considered "complete":
- **Similarity ≥ 0.85** (Excellent threshold)
- All public APIs ported
- All tests ported
- Documentation ported
- port-lint header present

## Next Commands

```bash
# Initialize task queue for systematic porting
cd tools/ast_distance
./ast_distance --init-tasks ../../tmp/android_system_properties/src rust ../../src kotlin tasks.json ../../AGENTS.md

# Get next high-priority task
./ast_distance --assign tasks.json <agent-id>
```
