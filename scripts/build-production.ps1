$ErrorActionPreference = 'Stop'
$Root = Split-Path $PSScriptRoot -Parent
Push-Location $Root
try {
  Push-Location frontend
  try {
    npm ci --ignore-scripts
    if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }
  } finally { Pop-Location }
  foreach ($File in @('index.html', 'sw.js', 'manifest.webmanifest')) {
    if (-not (Test-Path "frontend/dist/$File")) { throw "Missing production asset: $File" }
  }
  mvn -B -f backend/pom.xml -Pweb clean package @args
  if ($LASTEXITCODE -ne 0) { throw 'Backend build failed' }
  Write-Host 'Built backend/target/bridgego-server.jar (not deployed)'
} finally { Pop-Location }
