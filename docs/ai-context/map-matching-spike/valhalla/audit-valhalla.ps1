<#
.SYNOPSIS
    Deterministic index audit for the Valhalla trace_attributes spike (Phase 2A, World Discovery).

.DESCRIPTION
    Documentation-only tool. Cross-references the exact synthetic input trace
    (../shared/input-trace.csv) against the raw Valhalla trace_attributes response
    (response.json, preserved unmodified from the audited run) to produce a per-input-index
    audit table: matched/interpolated/unmatched, edge_index and its resolved edge name,
    distance_from_trace_point (meters -- see the unit note in
    ../../MAP_MATCHING_ENGINE_STUDY.md), distance_along_edge, and discontinuity flags.

    Uses PowerShell's native ConvertFrom-Json (a real JSON parser), never regex.

    Run from this directory:
        pwsh -File audit-valhalla.ps1
    or on Windows PowerShell 5.1:
        powershell -File audit-valhalla.ps1

.NOTES
    matched_points[] has a documented one-to-one correspondence with the input shape points
    (confirmed here too: 36 input rows, 36 matched_points entries).
#>

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$trace = Import-Csv (Join-Path $here "..\shared\input-trace.csv")
$response = Get-Content (Join-Path $here "response.json") -Raw | ConvertFrom-Json

Write-Output "matched_points count: $($response.matched_points.Count)"
Write-Output "edges count: $($response.edges.Count)"
Write-Output ""
Write-Output "index`tinput_lon`tinput_lat`tintent`ttype`tedge_index`tedge_name`tdistance_from_trace_point_m`tdistance_along_edge`tbegin_discontinuity`tend_discontinuity`tmatched_lon`tmatched_lat"

for ($i = 0; $i -lt $trace.Count; $i++) {
    $row = $trace[$i]
    $mp = $response.matched_points[$i]
    $edgeName = if ($null -ne $mp.edge_index -and $mp.edge_index -lt $response.edges.Count) {
        ($response.edges[$mp.edge_index].names -join '/')
    } else { '' }
    Write-Output "$i`t$($row.lon)`t$($row.lat)`t$($row.intent)`t$($mp.type)`t$($mp.edge_index)`t$edgeName`t$($mp.distance_from_trace_point)`t$($mp.distance_along_edge)`t$($mp.begin_route_discontinuity)`t$($mp.end_route_discontinuity)`t$($mp.lon)`t$($mp.lat)"
}
