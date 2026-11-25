param($port)
$pid_ = (Get-NetTCPConnection -LocalPort $port -State Listen).OwningProcess
$p = Get-Process -Id $pid_
$est = (Get-NetTCPConnection -LocalPort $port -State Established -ErrorAction SilentlyContinue | Measure-Object).Count
"PID $pid_ threads $($p.Threads.Count) workingset_MB $([math]::Round($p.WorkingSet64/1MB)) established $est"
