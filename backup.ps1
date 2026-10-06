# Backup del database Supabase garsal-apps
# Uso: .\backup.ps1
# Richiede: PostgreSQL client installato (pg_dump nel PATH)

$DB_HOST = "db.jajlmmdsjlvzgcxiiypk.supabase.co"
$DB_PORT = "5432"
$DB_USER = "postgres"
$DB_NAME = "postgres"

# Cartella backup accanto allo script
$BackupDir = Join-Path $PSScriptRoot "backups"
if (-not (Test-Path $BackupDir)) { New-Item -ItemType Directory -Path $BackupDir | Out-Null }

$Timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$Filename  = Join-Path $BackupDir "backup_$Timestamp.sql"

# Chiedi la password in modo sicuro (non appare sullo schermo)
$SecurePass = Read-Host "Password database Supabase" -AsSecureString
$BSTR       = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecurePass)
$PlainPass  = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($BSTR)
[System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($BSTR)

$env:PGPASSWORD = $PlainPass

Write-Host "Connessione a $DB_HOST..." -ForegroundColor Cyan

pg_dump `
  --host=$DB_HOST `
  --port=$DB_PORT `
  --username=$DB_USER `
  --dbname=$DB_NAME `
  --no-password `
  --format=plain `
  --no-owner `
  --no-acl `
  --schema=public `
  --file=$Filename

$env:PGPASSWORD = ""

if ($LASTEXITCODE -eq 0) {
  $Size = (Get-Item $Filename).Length / 1KB
  Write-Host ("Backup salvato: $Filename ({0:N0} KB)" -f $Size) -ForegroundColor Green
} else {
  Write-Host "Errore durante il backup." -ForegroundColor Red
  exit 1
}
