param([string]$Destination)
. (Join-Path $PSScriptRoot 'ToolCommon.ps1')
$package='com.tsd.czsetcollector.safe'
try {
    $adb=Get-ChzAdb; $serial=Get-ChzDevice $adb
    $pm=Invoke-ChzNative $adb @('-s',$serial,'shell','pm','path',$package)
    $paths=@($pm.Text -split '\r?\n' | Where-Object {$_ -match '^package:.*\.apk$'})
    if($pm.ExitCode -ne 0 -or $paths.Count -ne 1){throw 'Safe app not found or split APK unsupported'}
    if(!$Destination){$Destination=Join-Path $PSScriptRoot ('backups\safe-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,6))}
    $Destination=[IO.Path]::GetFullPath($Destination)
    if(Test-Path -LiteralPath $Destination){throw 'Backup destination exists; no files overwritten'}
    New-Item -ItemType Directory -Path $Destination | Out-Null
    $apk=Join-Path $Destination 'installed-safe.apk'
    $pull=Invoke-ChzNative $adb @('-s',$serial,'pull',$paths[0].Substring(8).Trim(),$apk)
    if($pull.ExitCode -ne 0){throw 'Cannot read installed APK'}
    $identity=Get-ChzIdentity $apk; $signer=Get-ChzSigner $apk
    if($identity.Package -ne $package -or $identity.Code -lt 9){throw 'Unsupported app version; no replacement performed'}
    if($identity.Code -eq 9) {
        . (Join-Path $PSScriptRoot 'Backup_131.ps1')
        $result=Export-Chz131Data $adb $serial $Destination
        $request=$null
    } else {
    $request=[guid]::NewGuid().ToString('D')
    $r=Invoke-ChzNative $adb @('-s',$serial,'shell','am','start','-n',($package+'/com.tsd.czsetcollector.MainActivity'),'--ez','chz_export_backup','true','--es','chz_backup_request',$request)
    if($r.ExitCode -ne 0){throw 'Cannot open backup dialog'}
    Write-Host 'Confirm Save backup on the TSD. Waiting up to 60 seconds...'
    $remote='/sdcard/Android/data/'+$package+'/files/backup/'
    $deadline=[DateTime]::UtcNow.AddSeconds(60); $result=$null
    do {
        $read=Invoke-ChzNative $adb @('-s',$serial,'shell','cat',($remote+'backup-result.json'))
        if($read.ExitCode -eq 0){
            try{$candidate=$read.Text|ConvertFrom-Json}catch{$candidate=$null}
            if($candidate -and $candidate.verified -eq $true -and $candidate.requestId -eq $request){$result=$candidate;break}
        }
        Start-Sleep -Seconds 2
    } while([DateTime]::UtcNow -lt $deadline)
    if(!$result){throw 'Fresh backup not confirmed; no update'}
    foreach($file in @('chz-profiles-backup.json','chz-queue-backup.json')) {
        $p=Invoke-ChzNative $adb @('-s',$serial,'pull',($remote+$file),(Join-Path $Destination $file))
        if($p.ExitCode -ne 0){throw 'Cannot retrieve backup files'}
    }
    }
    $pp=Assert-ChzEnvelope (Join-Path $Destination 'chz-profiles-backup.json') 'cz-profiles-backup'
    $qp=Assert-ChzEnvelope (Join-Path $Destination 'chz-queue-backup.json') 'cz-queue-backup'
    if((Get-ChzHash $pp) -ne $result.profilesSha256 -or (Get-ChzHash $qp) -ne $result.queueSha256){throw 'Backup result checksum mismatch'}
    $p=$pp|ConvertFrom-Json; $q=$qp|ConvertFrom-Json
    if(@($p.profiles).Count -ne $result.profileCount -or @($q.jobs).Count -ne $result.jobCount -or ($null -ne $q.current) -ne $result.hasCurrent){throw 'Backup counts mismatch'}
    $files=[ordered]@{}
    foreach($f in @(Get-ChildItem -LiteralPath $Destination -File)){$files[$f.Name]=(Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    $report=[ordered]@{format='cz-app-backup';version=1;verified=$true;serial=$serial;package=$package;installedVersion=$identity.Version;installedVersionCode=$identity.Code;signerSha256=$signer;createdUtc=[DateTime]::UtcNow.ToString('o');profilesCount=@($p.profiles).Count;queueJobs=@($q.jobs).Count;hasCurrent=($null -ne $q.current);profilesSha256=$result.profilesSha256;queueSha256=$result.queueSha256;requestId=$request;files=$files;secretsExported=$false}
    Write-ChzUtf8 (Join-Path $Destination 'verified-backup.json') ($report|ConvertTo-Json -Depth 12)
    Write-Host 'BACKUP VERIFIED. Profiles, queue and installed APK saved locally.'
    Write-Output $Destination
} catch {Write-Host ('STOP: '+$_.Exception.Message) -ForegroundColor Red;exit 1}
