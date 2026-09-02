$host.UI.RawUI.WindowTitle = "CONTROL - matar al lider Besu"
Write-Host "Matando al contenedor del nodo lider (raft-node1)..." -ForegroundColor Yellow
$env:MSYS_NO_PATHCONV = "1"
docker rm -f raft-node1
$hora = Get-Date -Format "HH:mm:ss.fff"
Write-Host "raft-node1 (lider) eliminado a las $hora" -ForegroundColor Red
