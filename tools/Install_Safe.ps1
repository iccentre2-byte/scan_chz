param([string]$BackupDirectory)
. (Join-Path $PSScriptRoot 'ToolCommon.ps1')
$expectedSigner='fbad4349bc4ad7aff13ddb98458b9160086b6d4a19056155fad57eb776e7d653'
$oldPackage='com.tsd.czsetcollector.pilot'
try {
    $adb=Get-ChzAdb; $serial=Get-ChzDevice $adb
    if(!$BackupDirectory){$BackupDirectory=& (Join-Path $PSScriptRoot 'Backup_TSD.ps1')}
    $BackupDirectory=[IO.Path]::GetFullPath($BackupDirectory)
    $reportPath=Join-Path $BackupDirectory 'verified-backup.json'
    if(!(Test-Path -LiteralPath $reportPath)){throw 'A verified USB backup is required before installation'}
    $report=[IO.File]::ReadAllText($reportPath,$ChzUtf8) | ConvertFrom-Json
    if($report.format -ne 'cz-device-backup' -or $report.version -ne 1 -or $report.verified -ne $true -or $report.serial -ne $serial -or $report.package -ne $oldPackage){throw 'Backup does not match this TSD'}
    foreach($entry in $report.files.PSObject.Properties){
        if($entry.Name -notin @('profiles-transfer.json','profiles-original.json','queue-transfer.json','queue-original.json','installed-1.3.0.apk')){throw 'Unknown backup file'}
        if((Get-FileHash -LiteralPath (Join-Path $BackupDirectory $entry.Name) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.Value){throw 'Backup file checksum differs; no installation'}
    }
    foreach($required in @('profiles-transfer.json','queue-transfer.json','installed-1.3.0.apk')){if(!$report.files.PSObject.Properties[$required]){throw 'Backup incomplete'}}
    $profilePayload=Assert-ChzEnvelope (Join-Path $BackupDirectory 'profiles-transfer.json') 'cz-profiles-backup'
    $queuePayload=Assert-ChzEnvelope (Join-Path $BackupDirectory 'queue-transfer.json') 'cz-queue-backup'
    $profileHash=Get-ChzHash $profilePayload; $queueHash=Get-ChzHash $queuePayload
    $manifest=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'release-manifest.json'),$ChzUtf8) | ConvertFrom-Json
    if($manifest.format -ne 'cz-apk-release' -or $manifest.version -ne 1){throw 'Invalid release manifest'}
    $scratch=Join-Path $PSScriptRoot ('.work\'+[guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $scratch -Force | Out-Null
    $pm=Invoke-ChzNative $adb @('-s',$serial,'shell','pm','path',$oldPackage)
    $paths=@($pm.Text -split '\r?\n' | Where-Object {$_ -match '^package:.*\.apk$'})
    if($pm.ExitCode -ne 0 -or $paths.Count -ne 1){throw 'Original app missing or split APK unsupported'}
    $currentApk=Join-Path $scratch 'installed-current.apk'
    $r=Invoke-ChzNative $adb @('-s',$serial,'pull',$paths[0].Substring(8).Trim(),$currentApk)
    if($r.ExitCode -ne 0){throw 'Cannot recheck installed APK'}
    $installedSigner=Get-ChzSigner $currentApk; $installedIdentity=Get-ChzIdentity $currentApk
    if($installedSigner -ne $report.signerSha256 -or $installedIdentity.Code -ne $report.installedVersionCode -or (Get-FileHash -LiteralPath $currentApk -Algorithm SHA256).Hash.ToLowerInvariant() -ne $report.files.'installed-1.3.0.apk'){throw 'Installed APK changed since backup; run backup again'}
    $sameSigner=($installedSigner -eq $expectedSigner)
    $targetPackage=if($sameSigner){$oldPackage}else{'com.tsd.czsetcollector.safe'}
    $record=@($manifest.apks | Where-Object applicationId -eq $targetPackage)
    if($record.Count -ne 1 -or $record[0].signerSha256 -ne $expectedSigner -or $record[0].signatureVerified -ne $true -or $record[0].file -notin @('CHZ-1.3.1-safe.apk','CHZ-1.3.1-pilot-update.apk')){throw 'Release record missing or invalid'}
    $newApk=Join-Path $PSScriptRoot $record[0].file
    if((Get-FileHash -LiteralPath $newApk -Algorithm SHA256).Hash.ToLowerInvariant() -ne $record[0].sha256){throw 'New APK checksum mismatch'}
    $newIdentity=Get-ChzIdentity $newApk
    if((Get-ChzSigner $newApk) -ne $expectedSigner -or $newIdentity.Package -ne $targetPackage -or $newIdentity.Code -ne 9 -or $newIdentity.Version -ne '1.3.1'){throw 'New APK verification failed'}
    if($sameSigner -and $newIdentity.Code -lt $installedIdentity.Code){throw 'Downgrade refused'}
    $skipInstall=$false
    if(!$sameSigner) {
        $side=Invoke-ChzNative $adb @('-s',$serial,'shell','pm','path',$targetPackage)
        $sidePaths=@($side.Text -split '\r?\n' | Where-Object {$_ -match '^package:.*\.apk$'})
        if($sidePaths.Count -gt 0) {
            if($sidePaths.Count -ne 1){throw 'Existing side app requires separate backup'}
            $sideApk=Join-Path $scratch 'existing-safe.apk'
            $p=Invoke-ChzNative $adb @('-s',$serial,'pull',$sidePaths[0].Substring(8).Trim(),$sideApk)
            if($p.ExitCode -ne 0 -or (Get-FileHash -LiteralPath $sideApk -Algorithm SHA256).Hash.ToLowerInvariant() -ne $record[0].sha256){throw 'A different safe app already exists. Its data also requires backup; no replacement performed.'}
            $skipInstall=$true
        }
    }
    if(!$skipInstall) {
        $installArgs=if($sameSigner){@('-s',$serial,'install','-r',$newApk)}else{@('-s',$serial,'install',$newApk)}
        $installed=Invoke-ChzNative $adb $installArgs 120000
        if($installed.ExitCode -ne 0 -or $installed.Text -notmatch '(?m)^Success\s*$'){throw 'Android refused installation. Original app and data remain. No destructive retry will run.'}
    }
    $mode=if($sameSigner){'UPDATE_WITH_MATCHING_SIGNATURE'}else{'SIDE_BY_SIDE_OLD_APP_PRESERVED'}
    $result=[ordered]@{mode=$mode;targetPackage=$targetPackage;backupDirectory=$BackupDirectory;signatureVerified=$true;installed=$true;transferVerified=$false}
    if($sameSigner) {
        # Internal ProfileRepository verifies and archives the original storage on first launch.
        $launch=Invoke-ChzNative $adb @('-s',$serial,'shell','am','start','-n',($targetPackage+'/com.tsd.czsetcollector.MainActivity'))
        if($launch.ExitCode -ne 0){throw 'Installed, but launch failed; keep backup and do not remove original data'}
        Write-Host 'Signature matched. Update installed; profiles migrate from the same private storage on launch.'
    } else {
        $migration='/sdcard/Android/data/'+$targetPackage+'/files/migration'
        $mk=Invoke-ChzNative $adb @('-s',$serial,'shell','mkdir','-p',$migration)
        if($mk.ExitCode -ne 0){throw 'Side app installed, but Android blocked staging. Import profiles-transfer.json and queue-transfer.json using the app buttons.'}
        foreach($name in @('profiles-transfer.json','queue-transfer.json')){
            $push=Invoke-ChzNative $adb @('-s',$serial,'push',(Join-Path $BackupDirectory $name),($migration+'/'+$name))
            if($push.ExitCode -ne 0){throw 'Side app installed, but staging failed. Original remains; keep backup.'}
        }
        $launch=Invoke-ChzNative $adb @('-s',$serial,'shell','am','start','-n',($targetPackage+'/com.tsd.czsetcollector.MainActivity'),'--ez','chz_import_staged','true')
        if($launch.ExitCode -ne 0){throw 'Side app installed, but launch failed; keep backup'}
        Write-Host 'Old app retained. Confirm transfer on the TSD screen. Waiting up to 60 seconds...'
        $deadline=[DateTime]::UtcNow.AddSeconds(60)
        do {
            $read=Invoke-ChzNative $adb @('-s',$serial,'shell','cat',($migration+'/migration-result.json'))
            if($read.ExitCode -eq 0){
                try{$m=$read.Text|ConvertFrom-Json}catch{$m=$null}
                if($m -and $m.verified -eq $true -and $m.profilesSha256 -eq $profileHash -and $m.queueSha256 -eq $queueHash){$result.transferVerified=$true;break}
            }
            Start-Sleep -Seconds 2
        } while([DateTime]::UtcNow -lt $deadline)
        if($result.transferVerified){Write-Host 'TRANSFER VERIFIED. All profiles and the queue were checked in the new app.'}
        else {Write-Host 'Installation completed; transfer is NOT yet confirmed. Confirm on TSD or use the separate import buttons. Keep 1.3.0 and the backup.'}
    }
    Write-ChzUtf8 (Join-Path $BackupDirectory 'installation-result.json') ($result|ConvertTo-Json -Depth 6)
} catch {Write-Host ('STOP: '+$_.Exception.Message) -ForegroundColor Red;exit 1}
