param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    if (-not $env:DEEPSEEK_API_KEY) {
        Write-Warning 'DEEPSEEK_API_KEY is not set. Login and history work; chat returns MODEL_NOT_CONFIGURED.'
    }
    $buildArgs = @('-B', '-ntp', 'package')
    if ($SkipTests) { $buildArgs += '-DskipTests' }
    & .\mvnw.cmd @buildArgs
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
    & java -jar .\target\voice-chat-backend-1.0.0.jar
    if ($LASTEXITCODE -ne 0) { throw 'Server exited with an error.' }
}
finally { Pop-Location }
