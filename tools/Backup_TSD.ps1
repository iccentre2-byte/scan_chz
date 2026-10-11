param([string]$Destination)
. (Join-Path $PSScriptRoot 'ToolCommon.ps1')
$package='com.tsd.czsetcollector.pilot'
try {
    $adb=Get-ChzAdb; $serial=Get-ChzDevice $adb
    if(!$Destination){$Destination=Join-Path $PSScriptRoot ('backups\'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,6))}
    $Destination=[IO.Path]::GetFullPath($Destination)
    if(Test-Path -LiteralPath $Destination){throw 'Backup destination already exists; no files overwritten'}
    New-Item -ItemType Directory -Path $Destination | Out-Null
    $packages=Invoke-ChzNative $adb @('-s',$serial,'shell','pm','list','packages',$package)
    if($packages.ExitCode -ne 0 -or ($packages.Text -split '\r?\n') -notcontains "package:$package"){throw 'Pilot package not found'}
    $probe=Invoke-ChzNative $adb @('-s',$serial,'exec-out','run-as',$package,'id')
    if($probe.ExitCode -ne 0 -or $probe.Text -notmatch 'uid='){throw 'run-as is unavailable: keep 1.3.0 installed and its data untouched. No backup confirmed.'}
    function Read-Private([string]$File,[switch]$Optional) {
        $r=Invoke-ChzNative $adb @('-s',$serial,'exec-out','run-as',$package,'cat',$File)
        if($r.ExitCode -ne 0){if($Optional){return $null};throw "Cannot read $File; backup not confirmed"}
        return $r.Text
    }
    function Choose-Private([string]$Base) {
        $bak=Read-Private ($Base+'.bak') -Optional
        if($null -ne $bak){return [pscustomobject]@{Path=($Base+'.bak');Text=$bak}}
        $main=Read-Private $Base -Optional
        if($null -eq $main){return $null}
        return [pscustomobject]@{Path=$Base;Text=$main}
    }
    # Read only; do not use AtomicFile.openRead(), which could restore/rename the backup on the device.
    $prefs=Choose-Private 'shared_prefs/cz_multi_profiles.xml'
    if($null -eq $prefs){throw 'Profile storage not found; backup not confirmed'}
    $queue=Choose-Private 'files/queue-v1.json'
    # Compare two reads to detect concurrent editing/scanning, without stopping the app.
    $prefsAgain=Choose-Private 'shared_prefs/cz_multi_profiles.xml'
    $queueAgain=Choose-Private 'files/queue-v1.json'
    if($prefsAgain.Path -ne $prefs.Path -or $prefsAgain.Text -cne $prefs.Text -or (($null -eq $queue) -ne ($null -eq $queueAgain)) -or ($queue -and ($queueAgain.Path -ne $queue.Path -or $queueAgain.Text -cne $queue.Text))){throw 'Data changed while reading. Pause scanning and run backup again. No backup confirmed.'}
    $xmlSettings=New-Object Xml.XmlReaderSettings
    $xmlSettings.DtdProcessing=[Xml.DtdProcessing]::Prohibit;$xmlSettings.XmlResolver=$null
    $reader=[Xml.XmlReader]::Create((New-Object IO.StringReader($prefs.Text)),$xmlSettings)
    $xml=New-Object Xml.XmlDocument;$xml.XmlResolver=$null
    try{$xml.Load($reader)}finally{$reader.Dispose()}
    $profileNode=$xml.SelectSingleNode('/map/string[@name="profiles_json"]')
    if(!$profileNode){throw 'profiles_json missing; backup not confirmed'}
    $profileRaw=$profileNode.InnerText
    $profiles=@($profileRaw | ConvertFrom-Json)
    if($profiles.Count -eq 0){throw 'No profiles found; backup not confirmed'}
    $safeProfiles=@(foreach($p in $profiles) {
        if($null -eq $p.inn -or $null -eq $p.pg -or $null -eq $p.title -or $null -eq $p.environment -or $null -eq $p.template -or $null -eq $p.template.setGtin -or $null -eq $p.template.expected){throw 'Incomplete profile data; backup not confirmed'}
        $expected=[ordered]@{}
        foreach($prop in $p.template.expected.PSObject.Properties){
            if($prop.Value -isnot [int] -and $prop.Value -isnot [long]){throw 'Invalid profile quantity'}
            $expected[$prop.Name]=$prop.Value
        }
        [ordered]@{inn=$p.inn;pg=$p.pg;title=$p.title;environment=$p.environment;template=[ordered]@{setGtin=$p.template.setGtin;expected=$expected}}
    })
    $selectedNode=$xml.SelectSingleNode('/map/int[@name="selected_profile_index"]')
    $selected=if($selectedNode){[int]$selectedNode.GetAttribute('value')}else{0}
    if($selected -lt 0 -or $selected -ge $safeProfiles.Count){throw 'Invalid selected profile index'}
    $payload=[ordered]@{profiles=$safeProfiles;selectedIndex=$selected} | ConvertTo-Json -Compress -Depth 40
    $profilesPath=Join-Path $Destination 'profiles-transfer.json'
    Write-ChzUtf8 $profilesPath (New-ChzEnvelope 'cz-profiles-backup' $payload)
    $verifiedPayload=Assert-ChzEnvelope $profilesPath 'cz-profiles-backup'
    if($verifiedPayload -cne $payload){throw 'Profiles read-back mismatch'}
    Write-ChzUtf8 (Join-Path $Destination 'profiles-original.json') $profileRaw
    $queueRaw=if($queue){$queue.Text}else{'{"jobs":[]}'}
    $parsedQueue=$queueRaw | ConvertFrom-Json
    if($null -eq $parsedQueue.jobs -or $parsedQueue.jobs -isnot [array]){throw 'Queue JSON incomplete'}
    $queuePath=Join-Path $Destination 'queue-transfer.json'
    Write-ChzUtf8 $queuePath (New-ChzEnvelope 'cz-queue-backup' $queueRaw)
    $verifiedQueue=Assert-ChzEnvelope $queuePath 'cz-queue-backup'
    if($verifiedQueue -cne $queueRaw){throw 'Queue read-back mismatch'}
    Write-ChzUtf8 (Join-Path $Destination 'queue-original.json') $queueRaw
    $pmPath=Invoke-ChzNative $adb @('-s',$serial,'shell','pm','path',$package)
    $apkLines=@($pmPath.Text -split '\r?\n' | Where-Object {$_ -match '^package:.*\.apk$'})
    if($pmPath.ExitCode -ne 0 -or $apkLines.Count -ne 1){throw 'Expected one installed APK; backup not confirmed'}
    $apkPath=$apkLines[0].Substring(8).Trim()
    $installedApk=Join-Path $Destination 'installed-1.3.0.apk'
    $pull=Invoke-ChzNative $adb @('-s',$serial,'pull',$apkPath,$installedApk)
    if($pull.ExitCode -ne 0){throw 'Cannot read installed APK'}
    $signer=Get-ChzSigner $installedApk; $identity=Get-ChzIdentity $installedApk
    if($identity.Package -ne $package -or $identity.Version -notmatch '^1\.3\.0'){throw 'Installed package/version differs; no automatic migration'}
    $files=@('profiles-transfer.json','profiles-original.json','queue-transfer.json','queue-original.json','installed-1.3.0.apk')
    $hashes=[ordered]@{}
    foreach($file in $files){$hashes[$file]=(Get-FileHash -LiteralPath (Join-Path $Destination $file) -Algorithm SHA256).Hash.ToLowerInvariant()}
    $report=[ordered]@{format='cz-usb-backup';version=1;verified=$true;serial=$serial;package=$package;installedVersion=$identity.Version;installedVersionCode=$identity.Code;signerSha256=$signer;createdUtc=[DateTime]::UtcNow.ToString('o');profilesCount=$safeProfiles.Count;selectedIndex=$selected;queueJobs=@($parsedQueue.jobs).Count;hasCurrent=($null -ne $parsedQueue.current);profileSource=$prefs.Path;queueSource=if($queue){$queue.Path}else{'absent'};files=$hashes;secretsExported=$false}
    Write-ChzUtf8 (Join-Path $Destination 'verified-backup.json') ($report | ConvertTo-Json -Depth 12)
    Write-ChzUtf8 (Join-Path $Destination 'REPORT.txt') ("BACKUP VERIFIED`r`nProfiles: $($safeProfiles.Count)`r`nQueue jobs: $(@($parsedQueue.jobs).Count)`r`nNo application data changed. No connection keys or signing credentials exported.`r`n")
    Write-Host "BACKUP VERIFIED: $Destination"
    Write-Output $Destination
} catch {
    Write-Host ('STOP: '+$_.Exception.Message) -ForegroundColor Red
    if($MyInvocation.InvocationName -ne '&'){exit 1}
    throw
}
