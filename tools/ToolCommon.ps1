$ErrorActionPreference='Stop'
$ChzUtf8=New-Object Text.UTF8Encoding($false,$true)
function Write-ChzUtf8([string]$Path,[string]$Text) { [IO.File]::WriteAllText($Path,$Text,$ChzUtf8) }
function Get-ChzHash([string]$Text) {
    $sha=[Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($ChzUtf8.GetBytes($Text)))).Replace('-','').ToLowerInvariant() }
    finally {$sha.Dispose()}
}
function Invoke-ChzNative([string]$Exe,[string[]]$Arguments,[int]$Timeout=60000) {
    $info=New-Object Diagnostics.ProcessStartInfo
    $info.FileName=$Exe; $info.UseShellExecute=$false; $info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    $info.Arguments=($Arguments | ForEach-Object {
        '"'+[regex]::Replace([regex]::Replace($_,'(\\*)"','$1$1\"'),'(\\+)$','$1$1')+'"'
    }) -join ' '
    $proc=New-Object Diagnostics.Process
    $proc.StartInfo=$info
    if(!$proc.Start()){throw 'Cannot start tool'}
    $memory=New-Object IO.MemoryStream
    $copy=$proc.StandardOutput.BaseStream.CopyToAsync($memory)
    $errors=$proc.StandardError.ReadToEndAsync()
    if(!$proc.WaitForExit($Timeout)) { $proc.Kill();throw 'Tool timed out; result unknown. Check installed version before retry. No destructive retry performed.' }
    [void]$copy.GetAwaiter().GetResult(); $errorText=$errors.GetAwaiter().GetResult()
    $bytes=$memory.ToArray();$memory.Dispose()
    $code=$proc.ExitCode;$proc.Dispose()
    if($bytes.Length -gt 32MB){throw 'Tool output too large'}
    return [pscustomobject]@{Bytes=$bytes;Text=$ChzUtf8.GetString($bytes);ErrorText=$errorText;ExitCode=$code}
}
function Get-ChzAdb {
    $adb=Join-Path $PSScriptRoot '.tools\adb\adb.exe'
    if(!(Test-Path -LiteralPath $adb)) {
        $found=Get-Command adb.exe -ErrorAction SilentlyContinue
        if($found){return $found.Source}
        throw 'Bundled ADB not found. Extract the complete CHZ-1.3.4-Windows-Safe.zip first.'
    }
    return $adb
}
function Get-ChzDevice([string]$Adb) {
    $result=Invoke-ChzNative $Adb @('devices','-l')
    if($result.ExitCode -ne 0){throw 'ADB unavailable'}
    $lines=@($result.Text -split '\r?\n' | Where-Object {$_ -match '^([^\s]+)\s+device(?:\s|$)'})
    if($lines.Count -eq 0){throw 'No connected TSD. Connect by USB or pair through Android Wi-Fi debugging.'}
    if($lines.Count -gt 1) {
        # Android can expose the same paired TSD via both an IP address and an mDNS alias.
        $hardware=@(foreach($line in $lines){
            $token=($line -split '\s+')[0]
            if($token -notmatch '^[A-Za-z0-9._:-]+$' -or $token -match '^emulator-'){throw 'Multiple or unsupported devices; no changes performed'}
            $id=Invoke-ChzNative $Adb @('-s',$token,'shell','getprop','ro.serialno')
            if($id.ExitCode -ne 0 -or !$id.Text.Trim()){throw 'Cannot distinguish connected devices'}
            $id.Text.Trim()
        })
        if(@($hardware | Select-Object -Unique).Count -ne 1){throw 'More than one TSD connected; disconnect the others'}
    }
    $serial=($lines[0] -split '\s+')[0]
    if($serial -notmatch '^[A-Za-z0-9._:-]+$' -or $serial -match '^emulator-' -or ($serial.Contains(':') -and $serial -notmatch '^(?:[0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{1,5}$')) {throw 'A physical TSD is required'}
    return $serial
}
function Get-ChzSigner([string]$Apk) {
    $java=Join-Path $PSScriptRoot '.tools\java\bin\java.exe'
    $jar=Join-Path $PSScriptRoot '.tools\apksigner.jar'
    if(!(Test-Path -LiteralPath $java) -or !(Test-Path -LiteralPath $jar)){throw 'APK verifier missing from extracted package'}
    $r=Invoke-ChzNative $java @('-jar',$jar,'verify','--verbose','--print-certs',$Apk)
    if($r.ExitCode -ne 0){throw 'APK signature verification failed'}
    $matches=[regex]::Matches($r.Text,'Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})')
    if($matches.Count -ne 1){throw 'Expected exactly one APK signer'}
    return $matches[0].Groups[1].Value
}
function Get-ChzIdentity([string]$Apk) {
    $aapt=Join-Path $PSScriptRoot '.tools\aapt.exe'
    $r=Invoke-ChzNative $aapt @('dump','xmltree',$Apk,'AndroidManifest.xml')
    $package=[regex]::Match($r.Text,'(?m)^    A: package="([^"]+)"')
    $code=[regex]::Match($r.Text,'(?m)^    A: android:versionCode\([^)]*\)=\(type 0x10\)0x([a-fA-F0-9]+)')
    $version=[regex]::Match($r.Text,'(?m)^    A: android:versionName\([^)]*\)="([^"]+)"')
    if($r.ExitCode -ne 0 -or !$package.Success -or !$code.Success -or !$version.Success){throw 'Cannot read APK identity'}
    return [pscustomobject]@{Package=$package.Groups[1].Value;Code=[Convert]::ToInt32($code.Groups[1].Value,16);Version=$version.Groups[1].Value}
}
function New-ChzEnvelope([string]$Format,[string]$Payload) {
    return [ordered]@{format=$Format;version=1;payloadJson=$Payload;sha256=(Get-ChzHash $Payload)} | ConvertTo-Json -Compress -Depth 40
}
function Assert-ChzEnvelope([string]$Path,[string]$Format) {
    $raw=[IO.File]::ReadAllText($Path,$ChzUtf8)
    $e=$raw | ConvertFrom-Json
    if($e.format -ne $Format -or $e.version -ne 1 -or (Get-ChzHash $e.payloadJson) -ne $e.sha256){throw 'Backup integrity check failed'}
    return $e.payloadJson
}
function Get-ChzCanonicalJson($Value) {
    if($null -eq $Value){return 'null'}
    if($Value -is [pscustomobject]){
        $parts=@(foreach($p in @($Value.PSObject.Properties|Sort-Object Name)){
            ($p.Name|ConvertTo-Json -Compress)+':'+(Get-ChzCanonicalJson $p.Value)
        })
        return '{'+($parts -join ',')+'}'
    }
    if($Value -is [array]){
        $parts=@(foreach($item in $Value){Get-ChzCanonicalJson $item})
        return '['+($parts -join ',')+']'
    }
    return ($Value|ConvertTo-Json -Compress)
}
