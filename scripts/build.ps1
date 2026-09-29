param(
    [ValidateSet('all', 'shell', 'wayland')][string]$Component = 'all',
    [string]$KeystoreDir = '',
    [string]$JavaHome = 'D:/MyProfile/Java/TencentKona-21',
    [string]$AndroidHome = 'D:/MyProfile/AndroidSDK',
    [string]$Python = 'D:/MyProfile/Python3/python.exe',
    [string]$Bash = 'D:/MyProfile/Git/bin/bash.exe'
)
$ErrorActionPreference = 'Stop'
if (-not $KeystoreDir) { $KeystoreDir = Join-Path (Split-Path $PSScriptRoot -Parent) 'keystore' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidHome
$env:ANLAND_KEYSTORE_DIR = $KeystoreDir
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = 'D:/MyProfile/.gradle' }
& $Python (Join-Path $PSScriptRoot 'build-apps.py') --component $Component --keystore-dir $KeystoreDir --bash $Bash
exit $LASTEXITCODE
