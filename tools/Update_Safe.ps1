param([string]$BackupDirectory)
. (Join-Path $PSScriptRoot 'ToolCommon.ps1')
$package='com.tsd.czsetcollector.safe'
$expectedSigner='fbad4349bc4ad7aff13ddb98458b9160086b6d4a19056155fad57eb776e7d653'
try {
    $adb=Get-ChzAdb; $serial=Get-ChzDevice $adb
    if(!$BackupDirectory){$BackupDirectory=& (Join-Path $PSScriptRoot 'Backup_App.ps1')}
    $reportPath=Join-Path $BackupDirectory 'verified-backup.json'
    $r=[IO.File]::ReadAllText($reportPath,$ChzUtf8)|ConvertFrom-Json
    if($r.format -ne 'cz-app-backup' -or $r.version -ne 1 -or $r.verified -ne $true -or $r.serial -ne $serial -or $r.package -ne $package -or $r.signerSha256 -ne $expectedSigner){throw 'Verified backup for this safe app is required'}
    $age=[DateTime]::UtcNow.Subtract([DateTime]::Parse($r.createdUtc).ToUniversalTime()).TotalMinutes
    if($age -lt -1 -or $age -gt 15){throw 'Backup timestamp is invalid or too old; create a fresh backup before updating'}
    foreach($f in $r.files.PSObject.Properties){
        if($f.Name -notin @('installed-safe.apk','installed-1.3.1.apk','chz-profiles-backup.json','chz-queue-backup.json','profiles-transfer.json','queue-transfer.json')){throw 'Unknown backup file'}
        if((Get-FileHash -LiteralPath (Join-Path $BackupDirectory $f.Name) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $f.Value){throw 'Backup file checksum mismatch'}
    }
    $profileFile=if($r.files.'chz-profiles-backup.json'){'chz-profiles-backup.json'}else{'profiles-transfer.json'}
    $queueFile=if($r.files.'chz-queue-backup.json'){'chz-queue-backup.json'}else{'queue-transfer.json'}
    $apkFile=if($r.files.'installed-safe.apk'){'installed-safe.apk'}else{'installed-1.3.1.apk'}
    $pp=Assert-ChzEnvelope (Join-Path $BackupDirectory $profileFile) 'cz-profiles-backup'
    $qp=Assert-ChzEnvelope (Join-Path $BackupDirectory $queueFile) 'cz-queue-backup'
    if((Get-ChzHash $pp) -ne $r.profilesSha256 -or (Get-ChzHash $qp) -ne $r.queueSha256){throw 'Backup payload mismatch'}
    $manifest=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'release-manifest.json'),$ChzUtf8)|ConvertFrom-Json
    if($manifest.format -ne 'cz-apk-release' -or $manifest.version -ne 1){throw 'Invalid release manifest'}
    $records=@($manifest.apks|Where-Object applicationId -eq $package)
    if($records.Count -ne 1){throw 'Expected one safe APK'}
    $record=$records[0]
    if($record.file -ne 'CHZ-1.3.2-safe.apk' -or $record.versionCode -ne 10 -or $record.versionName -ne '1.3.2' -or $record.signerSha256 -ne $expectedSigner -or $record.signatureVerified -ne $true){throw 'Unexpected release record'}
    $apk=Join-Path $PSScriptRoot $record.file
    if((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant() -ne $record.sha256 -or (Get-ChzSigner $apk) -ne $expectedSigner){throw 'New APK checksum/signature mismatch'}
    $identity=Get-ChzIdentity $apk
    if($identity.Package -ne $package -or $identity.Version -ne '1.3.2' -or $identity.Code -ne 10 -or $identity.Code -le $r.installedVersionCode){throw 'Unexpected version or non-upgrade'}
    $scratch=Join-Path $PSScriptRoot ('.work\'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $scratch -Force|Out-Null
    $pm=Invoke-ChzNative $adb @('-s',$serial,'shell','pm','path',$package)
    $paths=@($pm.Text -split '\r?\n'|Where-Object {$_ -match '^package:.*\.apk$'})
    if($pm.ExitCode -ne 0 -or $paths.Count -ne 1){throw 'Installed safe app is unavailable'}
    $current=Join-Path $scratch 'current.apk'
    $pull=Invoke-ChzNative $adb @('-s',$serial,'pull',$paths[0].Substring(8).Trim(),$current)
    if($pull.ExitCode -ne 0 -or (Get-FileHash -LiteralPath $current -Algorithm SHA256).Hash.ToLowerInvariant() -ne $r.files.$apkFile -or (Get-ChzSigner $current) -ne $expectedSigner){throw 'Installed APK changed since backup'}
    $install=Invoke-ChzNative $adb @('-s',$serial,'install','-r',$apk) 120000
    if($install.ExitCode -ne 0 -or $install.Text -notmatch '(?m)^Success\s*$'){throw 'Android refused update. No uninstall or destructive retry performed.'}
    $launch=Invoke-ChzNative $adb @('-s',$serial,'shell','am','start','-n',($package+'/com.tsd.czsetcollector.MainActivity'))
    if($launch.ExitCode -ne 0){throw 'Update installed; launch failed. Keep the backup.'}
    $outcome=[ordered]@{installed=$true;package=$package;version='1.3.2';versionCode=10;signatureVerified=$true;backupVerified=$true;dataPreservationVerified=$false}
    Write-ChzUtf8 (Join-Path $BackupDirectory 'update-result.json') ($outcome|ConvertTo-Json)
    Write-Host 'Signed update installed. Confirm the new backup on the TSD to verify preservation.'
    $after=& (Join-Path $PSScriptRoot 'Backup_App.ps1')
    $afterP=Assert-ChzEnvelope (Join-Path $after 'chz-profiles-backup.json') 'cz-profiles-backup'
    $afterQ=Assert-ChzEnvelope (Join-Path $after 'chz-queue-backup.json') 'cz-queue-backup'
    if((Get-ChzCanonicalJson ($pp|ConvertFrom-Json)) -cne (Get-ChzCanonicalJson ($afterP|ConvertFrom-Json)) -or (Get-ChzCanonicalJson ($qp|ConvertFrom-Json)) -cne (Get-ChzCanonicalJson ($afterQ|ConvertFrom-Json))){throw 'Data differs after update. Keep both backups; do not scan or submit until checked.'}
    $outcome.dataPreservationVerified=$true;$outcome.afterBackupDirectory=$after
    Write-ChzUtf8 (Join-Path $BackupDirectory 'update-result.json') ($outcome|ConvertTo-Json)
    Write-Host 'UPDATE VERIFIED. Every profile, template and queue record was preserved.'
} catch {Write-Host ('STOP: '+$_.Exception.Message) -ForegroundColor Red;exit 1}
