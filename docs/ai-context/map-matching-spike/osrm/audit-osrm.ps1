<#
.SYNOPSIS
    Deterministic index audit for the OSRM Match spike (Phase 2A, World Discovery).

.DESCRIPTION
    Documentation-only tool. Cross-references the exact synthetic input trace
    (../shared/input-trace.csv) against the raw OSRM /match response (response.json,
    preserved unmodified from the audited run) to produce a per-input-index audit table:
    whether OSRM matched or rejected (null) each point, its matched coordinate/distance/
    matchings_index/waypoint_index/alternatives_count, and the original intent label.

    Uses PowerShell's native ConvertFrom-Json (a real JSON parser), never regex, on both
    files -- this was itself a correction from an earlier draft of this study that used
    fragile regex extraction.

    Run from this directory:
        pwsh -File audit-osrm.ps1
    or on Windows PowerShell 5.1:
        powershell -File audit-osrm.ps1

.NOTES
    This script does not invent an explanation for any unexplained result (e.g. the second
    null tracepoint at index 20) -- it only reports exactly what the raw response contains.
#>

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$trace = Import-Csv (Join-Path $here "..\shared\input-trace.csv")
$response = Get-Content (Join-Path $here "response.json") -Raw | ConvertFrom-Json

Write-Output "code: $($response.code)"
Write-Output "matchings count: $($response.matchings.Count)"
for ($m = 0; $m -lt $response.matchings.Count; $m++) {
    Write-Output "matchings[$m].confidence = $($response.matchings[$m].confidence)"
}
Write-Output ""
Write-Output "index`tinput_lon`tinput_lat`tintent`ttracepoint`tmatched_lon`tmatched_lat`tdistance_m`tmatchings_index`twaypoint_index`talternatives_count"

for ($i = 0; $i -lt $trace.Count; $i++) {
    $row = $trace[$i]
    $tp = $response.tracepoints[$i]
    if ($null -eq $tp) {
        Write-Output "$i`t$($row.lon)`t$($row.lat)`t$($row.intent)`tNULL`t-`t-`t-`t-`t-`t-"
    } else {
        Write-Output "$i`t$($row.lon)`t$($row.lat)`t$($row.intent)`tmatched`t$($tp.location[0])`t$($tp.location[1])`t$($tp.distance)`t$($tp.matchings_index)`t$($tp.waypoint_index)`t$($tp.alternatives_count)"
    }
}
