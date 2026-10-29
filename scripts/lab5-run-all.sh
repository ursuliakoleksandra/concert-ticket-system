#!/usr/bin/env bash
# ЛР5: повний прогін навантажувальних досліджень (≈ 20–25 хв).
#   1. Підготовка: вузли з підвищеним rate limit (інакше k6 отримав би 429), кеш увімкнено, Round Robin
#   2. Специфікація середовища                      → results/lab5-environment.txt
#   3. Scenario A — Read-Intensive (10→200 VUs)      → results/lab5-scenario-a-read.*
#   4. Caching Impact (без кешу / з кешем)           → results/lab5-cache-impact.md
#   5. Scaling Impact (1 / 2 / 3 вузли)               → results/lab5-scaling.md
#   6. Scenario B — Write-Intensive (10→200 VUs)     → results/lab5-scenario-b-write.*
#   7. Scenario C — Complex Workflow (10→200 VUs)    → results/lab5-scenario-c-workflow.*
#   8. Повернення налаштувань за замовчуванням
# Порядок важливий: B і C створюють тисячі записів, тому читання (A та порівняння) йдуть першими.
source "$(dirname "$0")/lib.sh"

export RATE_LIMIT_PER_MINUTE=1000000

apps_with_env CACHE_ENABLED=true RATE_LIMIT_PER_MINUTE="$RATE_LIMIT_PER_MINUTE"
bash scripts/lb-switch.sh round-robin >/dev/null
bash scripts/lab5-environment.sh

step "Scenario A — Read-Intensive"
k6_run scenario-a-read.js CONDITIONS="3 instances, round-robin, cache enabled"

bash scripts/lab5-cache-impact.sh
bash scripts/lab5-scaling.sh

step "Scenario B — Write-Intensive"
k6_run scenario-b-write.js CONDITIONS="3 instances, round-robin, cache enabled"

step "Scenario C — Complex Workflow"
k6_run scenario-c-workflow.js CONDITIONS="3 instances, round-robin, cache enabled"

step "Повернення налаштувань за замовчуванням (rate limit 20/хв)"
apps_with_env CACHE_ENABLED=true RATE_LIMIT_PER_MINUTE=20

printf '\nГотово. Результати ЛР5 — у results/lab5-*\n'
