# ОБЯЗАТЕЛЬНО: используй cbm (Codebase Memory)

Проект проиндексирован в cbm под именем **`inkshelf`** (корень
`/home/rus/work/android/projects/inkshelf`). При любой работе с кодовой базой
сначала обращайся к графу cbm, а не к слепому чтению файлов.

## Рабочий процесс

1. **Индексируй после каждого изменения кода.**
   `index_repository(repo_path="/home/rus/work/android/projects/inkshelf")` — после любых правок
   (правки, рефакторинг, новые модули). Автослежение здесь **не работает**: проект MCP-сессии —
   `/home/rus/work/android`, поэтому `index_status` отдаёт `watch: false` (`not_session_project`),
   и индекс обновляется только вручную. `mode` по умолчанию `full` (с семантикой); `fast` —
   быстрая переиндексация; `async=true` требует демона, который живёт дольше вызова;
   `status=true` опрашивает текущую или последнюю задачу.
2. **Обзор:** `get_architecture(project="inkshelf", aspects=["all"])` — языки, пакеты, точки входа,
   горячие точки, границы, слои, кластеры, циклы.
3. **Поиск символов:** `search_graph(project="inkshelf", query=...)` — полнотекстовый BM25;
   `semantic_query=[...]` — векторный поиск. Эти два параметра **взаимоисключающие** — вызывай
   отдельными запросами. Regex/шаблон по имени — отдельные параметры `name_pattern` / `qn_pattern`.
4. **Литералы / текст:** `search_code(project="inkshelf", pattern=..., mode="compact"|"full"|"files")`.
5. **Связи и вызовы:** `trace_path(project="inkshelf", function_name="<qn>", direction="inbound"|"outbound"|"both", depth=N)`.
6. **Сложные запросы (multi-hop, агрегации):** `query_graph(project="inkshelf", query="MATCH ... RETURN ...")`.
7. **Исходник символа:** `get_code_snippet(project="inkshelf", qualified_name="<qn>")`;
   **структура файла:** `get_file_outline(project="inkshelf", file_path="<путь от корня репозитория>")`.
8. **Проверка свежести/полноты:** `check_index_coverage(project="inkshelf", paths=[...])`,
   `index_status(project="inkshelf", diagnostics="summary")`.
9. **Diff-импакт:** `detect_changes(project="inkshelf")` (база — `origin/HEAD`, иначе `main`).
   Сравнение двух снапшотов: `compare_graphs(base_project=..., target_project=...)` — параметра
   `project` у него нет, сравниваются **два проиндексированных проекта** (чтобы сравнить версии
   одного репозитория, проиндексируй его дважды с разными `name`).
10. **`doc_links` в `index_status` — это не список ошибок.** Неразрешённые ссылки бывают:
    `external` (API вне репозитория), `below_bar_tier` (документ → документ/скрипт),
    `ambiguous` (голое имя файла: из одного XML граф делает два узла, Module + File),
    `missing` (имена XML-стилей и пути-каталоги — не узлы графа: от XML есть только Module + File),
    `graph_gap` (`R.drawable#имя`). Прежде чем считать это багом, проверь цель на диске.
