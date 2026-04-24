package com.couchbase.perf.shared.main

import com.couchbase.context.StageContext
import com.couchbase.context.environments.Environment
import com.couchbase.perf.shared.config.ConfigParser
import com.couchbase.perf.shared.config.PerfConfig
import com.couchbase.perf.shared.config.Run
import groovy.json.JsonOutput
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic

@CompileStatic
class CountRuns {

    static StageContext buildContext() {
        def ctx = new StageContext()
        ctx.env = new Environment()
        ctx.dryRun = true
        ctx.force = false
        ctx.runsRequired = 1
        // Stub jc (jobConfig) with just enough for ctx.skipDriverDockerBuild() etc. not to NPE
        ctx.jc = [variables: [skipDriverDockerBuild: true, skipPerformerDockerBuild: true, stopOnFailure: false]]
        return ctx
    }

    @CompileDynamic
    static void applyClusterDefaults(PerfConfig config) {
        // When running without a live cluster, fill in the fields that includeRun and includeVariablesThatApplyToThisRun
        // read, so permutation filtering works correctly.
        config.matrix.clusters.forEach(cluster -> {
            if (cluster.nodeCount == null) cluster.nodeCount = 1
            if (cluster.memory == null) cluster.memory = 28000
            if (cluster.cpuCount == null) cluster.cpuCount = 16
            if (cluster.replicas == null) cluster.replicas = 0
            if (cluster.version == null) cluster.version = "8.0.0"
            // isCouchbase2() checks this field; default to classic protocol so non-protostellar exclusions apply
            if (cluster.connection_string_performer == null) cluster.connection_string_performer = "couchbase://localhost"
            if (cluster.connection_string_driver == null) cluster.connection_string_driver = "couchbase://localhost"
        })
    }

    @CompileDynamic
    static String workloadLabel(int idx, Object workload) {
        try {
            def ops = workload.operations as List
            if (ops == null || ops.isEmpty()) return "Workload ${idx + 1}"
            def first = ops[0] as Map
            def op = first.get("op") as String

            if (op == null) {
                // Might be a transaction
                def txn = first.get("transaction") as Map
                if (txn != null) {
                    def txnOps = txn.get("ops") as List
                    def txnFirst = txnOps?.get(0) as Map
                    def txnOp = txnFirst?.get("op") as String ?: "?"
                    return "txn/${txnOp}"
                }
                return "Workload ${idx + 1}"
            }

            // workload.settings is a Settings object; .variables is List<Variable> with .name/.value/.values fields
            def variables = workload.settings?.variables ?: []
            def varNames = variables.collect { it.name as String }

            String label = op
            if (varNames.contains("experimentName")) {
                def expVar = variables.find { it.name == "experimentName" }
                def expVal = expVar?.values ?: expVar?.value
                if (expVal instanceof List) label += " (${(expVal as List)[0]})"
                else if (expVal != null) label += " (${expVal})"
            } else if (varNames.contains("horizontalScaling")) {
                def hsVar = variables.find { it.name == "horizontalScaling" }
                def hsVals = hsVar?.values
                if (hsVals instanceof List && (hsVals as List).size() > 1) label += " (h-scaling)"
            }

            // Disambiguate gets by pool selection strategy
            def docLocation = first.get("docLocation") as Map
            if (docLocation != null) {
                def strategy = docLocation.get("poolSelectionStrategy") as String
                if (strategy != null) label += " (${strategy})"
            }

            return label
        } catch (ignored) {
            return "Workload ${idx + 1}"
        }
    }

    // Derive a workload label directly from a run's variables and operations.
    // Must produce the same string as workloadLabel() for the corresponding config entry.
    @CompileDynamic
    static String runWorkloadLabel(Run run) {
        try {
            def ops = run.workload.operations() as List
            if (ops == null || ops.isEmpty()) return "unknown"
            def first = ops[0] as Map
            def op = first.get("op") as String

            if (op == null) {
                def txn = first.get("transaction") as Map
                if (txn != null) {
                    def txnOps = txn.get("ops") as List
                    def txnFirst = txnOps?.get(0) as Map
                    def txnOp = txnFirst?.get("op") as String ?: "?"
                    return "txn/${txnOp}"
                }
                return "unknown"
            }

            // experimentName in the run's variables uniquely identifies experiment workloads
            def runVars = run.workload.settings().variables()
            def expName = runVars.find { it.name() == "experimentName" }?.value()
            if (expName != null) {
                def docLocation = first.get("docLocation") as Map
                def strategy = docLocation?.get("poolSelectionStrategy") as String
                return strategy ? "${op} (${expName}) (${strategy})" : "${op} (${expName})"
            }

            def docLocation = first.get("docLocation") as Map
            def strategy = docLocation?.get("poolSelectionStrategy") as String
            if (strategy != null) return "${op} (${strategy})"

            return op
        } catch (ignored) {
            return "unknown"
        }
    }

    @CompileDynamic
    static Map<String, Object> buildReportData(List<Run> runs, List<Object> workloads) {
        // Pre-populate byWorkload with ordered keys from config to preserve display order
        def workloadLabels = [:]
        workloads.eachWithIndex { w, i -> workloadLabels[i] = workloadLabel(i, w) }

        def byLanguage = new TreeMap<String, Integer>()
        def byWorkload = new LinkedHashMap<String, Integer>()
        def byLangAndWorkload = new TreeMap<String, Map<String, Integer>>()
        def byLangVersion = new TreeMap<String, Map<String, Integer>>()
        def varCounts = new TreeMap<String, Set<Object>>()

        workloadLabels.each { idx, label -> byWorkload[label as String] = 0 }

        runs.forEach(run -> {
            def lang = run.impl.language
            def ver = run.impl.version()

            // Derive label from the run itself — avoids ambiguity when two workloads share identical operations
            def wLabel = runWorkloadLabel(run)

            // byLanguage
            byLanguage[lang] = (byLanguage[lang] ?: 0) + 1

            // byWorkload
            byWorkload[wLabel] = (byWorkload[wLabel] ?: 0) + 1

            // byLangAndWorkload
            if (!byLangAndWorkload.containsKey(lang)) byLangAndWorkload[lang] = new LinkedHashMap<>()
            byLangAndWorkload[lang][wLabel] = (byLangAndWorkload[lang][wLabel] ?: 0) + 1

            // byLangVersion
            if (!byLangVersion.containsKey(lang)) byLangVersion[lang] = new TreeMap<>()
            byLangVersion[lang][ver] = (byLangVersion[lang][ver] ?: 0) + 1

            // variable fan-out: collect distinct values per variable name
            run.workload.settings()?.variables()?.forEach(v -> {
                if (!varCounts.containsKey(v.name())) varCounts[v.name()] = new HashSet<>()
                varCounts[v.name()].add(v.value())
            })
        })

        // Convert varCounts to map of name -> count of distinct values
        def varFanout = varCounts.collectEntries { k, v -> [(k): (v as Set).size()] }

        return [
            totalRuns    : runs.size(),
            byLanguage   : byLanguage,
            byWorkload   : byWorkload,
            byLangWorkload: byLangAndWorkload,
            byLangVersion: byLangVersion,
            varFanout    : varFanout,
            workloadLabels: workloadLabels.values().toList(),
        ]
    }

    static String generateHtml(Map<String, Object> data, String configPath, Set<String> missingLanguages = []) {
        def json = JsonOutput.prettyPrint(JsonOutput.toJson(data))
        def totalRuns = data.totalRuns as int
        def estimatedHours = (totalRuns * 300.0 / 3600.0).round(1)

        return """\
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>FIT Perf Run Count Analysis</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js"></script>
<style>
  body { font-family: system-ui, sans-serif; margin: 0; padding: 16px; background: #f5f5f5; color: #222; }
  h1 { margin-top: 0; }
  .subtitle { color: #666; font-size: 0.9em; margin-top: -12px; margin-bottom: 20px; }
  .summary { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 28px; }
  .card { background: white; border-radius: 8px; padding: 16px 24px; box-shadow: 0 1px 4px rgba(0,0,0,.1); min-width: 140px; }
  .card-value { font-size: 2em; font-weight: bold; color: #1a73e8; }
  .card-label { font-size: 0.85em; color: #555; margin-top: 2px; }
  .chart-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-bottom: 20px; }
  .chart-box { background: white; border-radius: 8px; padding: 16px; box-shadow: 0 1px 4px rgba(0,0,0,.1); }
  .chart-box.wide { grid-column: span 2; }
  h2 { font-size: 1em; margin: 0 0 12px 0; color: #333; }
  canvas { max-height: 350px; }
  details { background: white; border-radius: 8px; padding: 12px 16px; box-shadow: 0 1px 4px rgba(0,0,0,.1); margin-bottom: 16px; }
  summary { cursor: pointer; font-weight: 600; }
  table { border-collapse: collapse; width: 100%; font-size: 0.9em; margin-top: 8px; }
  th, td { text-align: left; padding: 6px 10px; border-bottom: 1px solid #eee; }
  th { background: #f0f4ff; }
  .warn { color: #b36b00; font-size: 0.85em; margin-top: 4px; }
  @media (max-width: 700px) { .chart-grid { grid-template-columns: 1fr; } .chart-box.wide { grid-column: span 1; } }
</style>
</head>
<body>
<h1>FIT Perf Run Count Analysis</h1>
<div class="subtitle">Config: ${configPath}</div>

<div class="summary">
  <div class="card"><div class="card-value" id="total-runs"></div><div class="card-label">Total runs</div></div>
  <div class="card"><div class="card-value" id="est-hours"></div><div class="card-label">Est. serial hours (at 5 min/run)</div></div>
  <div class="card"><div class="card-value" id="sdk-count"></div><div class="card-label">SDK languages</div></div>
  <div class="card"><div class="card-value" id="workload-count"></div><div class="card-label">Workloads</div></div>
  <div class="card"><div class="card-value" id="ver-count"></div><div class="card-label">SDK versions total</div></div>
</div>
<p class="warn">&#9888; Serial hour estimate assumes 5 min per run and ignores cluster/performer setup time. Actual wall-clock time depends on parallelism.</p>
${missingLanguages ? '<p class="warn">&#9888; The following SDK languages resolved to 0 versions (network API unavailable or returned empty) and are absent from this report — counts are understated: <strong>' + missingLanguages.sort().join(', ') + '</strong></p>' : ''}

<div class="chart-grid">
  <div class="chart-box">
    <h2>Runs by SDK language</h2>
    <canvas id="chart-by-lang"></canvas>
  </div>
  <div class="chart-box">
    <h2>Runs by workload</h2>
    <canvas id="chart-by-workload"></canvas>
  </div>
  <div class="chart-box wide">
    <h2>Workload &times; language breakdown</h2>
    <canvas id="chart-heatmap"></canvas>
  </div>
  <div class="chart-box">
    <h2>Variable fan-out (distinct values per variable)</h2>
    <canvas id="chart-varfanout"></canvas>
  </div>
</div>

<details open>
  <summary>Runs by SDK version (per language)</summary>
  <div id="ver-charts" style="display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-top:12px;"></div>
</details>

<details>
  <summary>Raw data (JSON)</summary>
  <pre style="overflow:auto;font-size:0.8em;max-height:400px;">${json.replace('<', '&lt;').replace('>', '&gt;')}</pre>
</details>

<script>
const data = ${json};

const COLORS = [
  '#1a73e8','#e8710a','#0f9d58','#db4437','#ab47bc',
  '#00acc1','#ff7043','#8d6e63','#546e7a','#43a047',
  '#f4511e','#039be5','#7986cb','#e91e63','#00897b',
];

function hBar(canvasId, labels, values, color) {
  const sorted = labels.map((l,i) => [l, values[i]]).sort((a,b) => b[1]-a[1]);
  new Chart(document.getElementById(canvasId), {
    type: 'bar',
    data: {
      labels: sorted.map(x=>x[0]),
      datasets: [{ data: sorted.map(x=>x[1]), backgroundColor: color || '#1a73e8', borderRadius: 4 }]
    },
    options: {
      indexAxis: 'y',
      plugins: { legend: { display: false } },
      scales: { x: { beginAtZero: true } },
      responsive: true, maintainAspectRatio: true,
    }
  });
}

// Summary cards
const totalRuns = data.totalRuns;
document.getElementById('total-runs').textContent = totalRuns.toLocaleString();
document.getElementById('est-hours').textContent = (totalRuns * 5 / 60).toFixed(1);
document.getElementById('sdk-count').textContent = Object.keys(data.byLanguage).length;
document.getElementById('workload-count').textContent = Object.keys(data.byWorkload).length;
document.getElementById('ver-count').textContent = Object.values(data.byLangVersion)
  .reduce((s, v) => s + Object.keys(v).length, 0);

// Runs by language
hBar('chart-by-lang', Object.keys(data.byLanguage), Object.values(data.byLanguage));

// Runs by workload
hBar('chart-by-workload', Object.keys(data.byWorkload), Object.values(data.byWorkload), '#0f9d58');

// Workload × language stacked bar
(function() {
  const langs = Object.keys(data.byLangWorkload);
  const workloads = Object.keys(data.byWorkload);
  const datasets = langs.map((lang, i) => ({
    label: lang,
    data: workloads.map(w => (data.byLangWorkload[lang] || {})[w] || 0),
    backgroundColor: COLORS[i % COLORS.length],
    borderRadius: 2,
  }));
  new Chart(document.getElementById('chart-heatmap'), {
    type: 'bar',
    data: { labels: workloads, datasets },
    options: {
      plugins: { legend: { position: 'right' } },
      scales: { x: { stacked: true }, y: { stacked: true, beginAtZero: true } },
      responsive: true, maintainAspectRatio: false,
      aspectRatio: 2.5,
    }
  });
  document.getElementById('chart-heatmap').style.maxHeight = '400px';
})();

// Variable fan-out
(function() {
  const entries = Object.entries(data.varFanout).sort((a,b) => b[1]-a[1]);
  hBar('chart-varfanout', entries.map(e=>e[0]), entries.map(e=>e[1]), '#ab47bc');
})();

// Per-language version charts
(function() {
  const container = document.getElementById('ver-charts');
  Object.entries(data.byLangVersion).forEach(([lang, vers], li) => {
    const div = document.createElement('div');
    div.style.cssText = 'background:white;border-radius:8px;padding:12px;box-shadow:0 1px 4px rgba(0,0,0,.1)';
    const h = document.createElement('h2');
    h.style.cssText = 'font-size:0.9em;margin:0 0 8px 0';
    h.textContent = lang;
    const canvas = document.createElement('canvas');
    div.appendChild(h);
    div.appendChild(canvas);
    container.appendChild(div);

    const entries = Object.entries(vers).sort((a,b) => b[1]-a[1]);
    new Chart(canvas, {
      type: 'bar',
      data: {
        labels: entries.map(e=>e[0]),
        datasets: [{ data: entries.map(e=>e[1]), backgroundColor: COLORS[li % COLORS.length], borderRadius: 3 }]
      },
      options: {
        indexAxis: 'y',
        plugins: { legend: { display: false } },
        scales: { x: { beginAtZero: true } },
        responsive: true,
      }
    });
  });
})();
</script>
</body>
</html>
"""
    }

    @CompileDynamic
    static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: countRuns <config.yaml> [output.html]")
            System.err.println("  config.yaml  Path to a FIT perf config YAML (e.g. fit-perf-scheduled.yaml)")
            System.err.println("  output.html  Path to write the HTML report (default: run-count-report.html)")
            System.exit(1)
        }

        def configPath = args[0]
        def outputPath = args.length > 1 ? args[1] : "run-count-report.html"

        println "Reading config from: ${configPath}"
        def config = ConfigParser.readPerfConfig(configPath)
        def ctx = buildContext()

        // Capture which languages were in the config before resolution so we can warn if any vanish
        def originalLanguages = config.matrix.implementations.collect { it.language }.toSet()

        println "Resolving SDK versions (may hit Maven/GitHub/PyPI/npm/crates.io)..."
        Execute.modifyConfigImplementations(ctx, config)

        def resolvedLanguages = config.matrix.implementations.collect { it.language }.toSet()
        def missingLanguages = originalLanguages - resolvedLanguages
        if (missingLanguages) {
            println "WARNING: The following languages resolved to 0 versions and will be absent from the report:"
            missingLanguages.each { println "  - ${it}" }
            println "  This usually means a network API call (GitHub, NuGet, crates.io, etc.) returned no results."
            println "  Run counts for these SDKs will be 0. The total may be significantly understated."
        }

        println "Applying cluster defaults..."
        applyClusterDefaults(config)

        println "Computing permutations..."
        def runs = ConfigParser.allPerms(ctx, config)

        println "Building report data..."
        def data = buildReportData(runs, config.matrix.workloads as List<Object>)

        println "Writing HTML to: ${outputPath}"
        new File(outputPath).text = generateHtml(data, configPath, missingLanguages)

        println "Done. Total runs: ${runs.size()}"
        def byLangSummary = (data.byLanguage as Map).collect { k, v -> "${k}=${v}" }.join(', ')
        println "  By language: ${byLangSummary}"
    }
}
