# Dot-source after ToolCommon.ps1. Defining this function never contacts a device.
function Export-Chz131Data([string]$adb,[string]$serial,[string]$destination) {
    $ErrorActionPreference='Stop'
    foreach($command in @('Invoke-ChzNative','Assert-ChzEnvelope','Get-ChzHash')) {
        if(!(Get-Command $command -ErrorAction SilentlyContinue)){throw 'Load ToolCommon.ps1 before calling Export-Chz131Data'}
    }
    if(!$adb -or $serial -notmatch '^[A-Za-z0-9._:-]+$' -or !$destination){throw 'Invalid backup parameters'}
    $destination=[IO.Path]::GetFullPath($destination)
    if(!(Test-Path -LiteralPath $destination)){New-Item -ItemType Directory -Path $destination | Out-Null}
    if(!(Test-Path -LiteralPath $destination -PathType Container)){throw 'Backup destination is not a directory'}
    $package='com.tsd.czsetcollector.safe'
    $remote='/sdcard/Android/data/'+$package+'/files/'
    $dump='/sdcard/chz-backup-131-ui-'+[guid]::NewGuid().ToString('N')+'.xml'
    $names=@('chz-profiles-backup.json','chz-queue-backup.json')
    foreach($name in $names) {
        if(Test-Path -LiteralPath (Join-Path $destination $name)){throw 'Backup files already exist locally; nothing overwritten'}
    }

    function Invoke-131Adb([string[]]$Arguments) {
        $r=Invoke-ChzNative $adb (@('-s',$serial)+$Arguments) 20000
        if($r.ExitCode -ne 0){throw ('Cannot complete safe 1.3.1 backup action: '+($Arguments -join ' '))}
        return $r
    }
    function Read-131Ui {
        $null=Invoke-131Adb @('shell','uiautomator','dump',$dump)
        $raw=(Invoke-131Adb @('shell','cat',$dump)).Text
        if($raw.Length -gt 2MB){throw 'Unexpected UI dump size'}
        $xml=New-Object Xml.XmlDocument
        $xml.XmlResolver=$null
        $settings=New-Object Xml.XmlReaderSettings
        $settings.DtdProcessing=[Xml.DtdProcessing]::Prohibit
        $reader=[Xml.XmlReader]::Create((New-Object IO.StringReader($raw)),$settings)
        try{$xml.Load($reader)}finally{$reader.Dispose()}
        if($xml.DocumentElement.Name -ne 'hierarchy'){throw 'Cannot observe the TSD screen'}
        return $xml
    }
    function Find-131Node($Ui,[string]$Id) {
        return @($Ui.SelectNodes('//node') | Where-Object {
            $_.GetAttribute('package') -eq $package -and $_.GetAttribute('resource-id') -match (':id/'+[regex]::Escape($Id)+'$')
        })
    }
    function Assert-131Screen($Ui) {
        if(@(Find-131Node $Ui 'etProfileTitle').Count -gt 0 -or
            @(Find-131Node $Ui 'profileEditor').Count -gt 0 -or
            @($Ui.SelectNodes('//node') | Where-Object {$_.GetAttribute('package') -eq $package -and $_.GetAttribute('text') -eq 'Закрыть настройки без сохранения'}).Count -gt 0) {
            throw 'Profile editor is open. Save or close the draft on the TSD before backup; nothing changed.'
        }
        foreach($n in @(Find-131Node $Ui 'btnResetCurrentSet')) {
            if($n.GetAttribute('enabled') -eq 'true'){throw 'An unfinished set is present; backup navigation stopped without resetting it'}
        }
        foreach($n in @(Find-131Node $Ui 'tvCurrentSet')) {
            if($n.GetAttribute('text') -ne 'Текущий набор не открыт'){throw 'An unfinished or unknown current set is present; backup stopped'}
        }
        foreach($n in @(Find-131Node $Ui 'tvScanState')) {
            if($n.GetAttribute('text') -match 'ОСТАНОВЛЕНО|QR подключения|Сканируйте пачки'){throw 'TSD is not in a safe backup state'}
        }
    }
    function Get-131Bounds($Node) {
        $m=[regex]::Match($Node.GetAttribute('bounds'),'^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
        if(!$m.Success){throw 'Invalid observed UI bounds'}
        $b=@(1..4 | ForEach-Object {[int]$m.Groups[$_].Value})
        if($b[2] -le $b[0] -or $b[3] -le $b[1]){throw 'UI target has no visible bounds'}
        return $b
    }
    function Seek-131Node([string]$Id,[string]$Page,[string]$Direction='down') {
        for($attempt=0;$attempt -le 5;$attempt++) {
            $ui=Read-131Ui;Assert-131Screen $ui
            $nodes=@(Find-131Node $ui $Id)
            if($nodes.Count -eq 1){return $nodes[0]}
            if($nodes.Count -gt 1 -or $attempt -eq 5){throw ('Cannot safely locate '+$Id+' on the TSD')}
            # This dump is read immediately before the swipe. Only the observed app ScrollView is used.
            $pages=@(Find-131Node $ui $Page | Where-Object {$_.GetAttribute('enabled') -eq 'true' -and $_.GetAttribute('scrollable') -eq 'true'})
            if($pages.Count -ne 1){throw 'Expected app scroll area is not visible; no generic screen gestures performed'}
            $b=Get-131Bounds $pages[0];$x=[int](($b[0]+$b[2])/2)
            $top=[int]($b[1]+($b[3]-$b[1])*0.2);$bottom=[int]($b[1]+($b[3]-$b[1])*0.8)
            if($Direction -eq 'up'){$from=$top;$to=$bottom}else{$from=$bottom;$to=$top}
            $null=Invoke-131Adb @('shell','input','swipe',"$x","$from","$x","$to",'350')
            Start-Sleep -Milliseconds 250
        }
    }
    function Tap-131Id([string]$Id,[string]$Text) {
        # Never tap from stale coordinates; read and match the exact allowed action again.
        $ui=Read-131Ui;Assert-131Screen $ui
        $nodes=@(Find-131Node $ui $Id | Where-Object {$_.GetAttribute('enabled') -eq 'true' -and $_.GetAttribute('clickable') -eq 'true' -and $_.GetAttribute('text') -eq $Text})
        if($nodes.Count -ne 1){throw ('Expected safe backup button is not visible: '+$Id)}
        $b=Get-131Bounds $nodes[0]
        $null=Invoke-131Adb @('shell','input','tap',"$([int](($b[0]+$b[2])/2))","$([int](($b[1]+$b[3])/2))")
        Start-Sleep -Milliseconds 250
    }
    function Tap-131Dialog([string]$Title,[string]$Positive) {
        $ui=Read-131Ui;Assert-131Screen $ui
        $owned=@($ui.SelectNodes('//node') | Where-Object {$_.GetAttribute('package') -eq $package})
        $titles=@($owned | Where-Object {$_.GetAttribute('text') -eq $Title})
        $buttons=@($owned | Where-Object {$_.GetAttribute('resource-id') -eq 'android:id/button1' -and $_.GetAttribute('text') -eq $Positive -and $_.GetAttribute('enabled') -eq 'true' -and $_.GetAttribute('clickable') -eq 'true'})
        if($titles.Count -ne 1 -or $buttons.Count -ne 1){throw 'Expected safe navigation/export confirmation is absent; nothing confirmed'}
        $b=Get-131Bounds $buttons[0]
        $null=Invoke-131Adb @('shell','input','tap',"$([int](($b[0]+$b[2])/2))","$([int](($b[1]+$b[3])/2))")
        Start-Sleep -Milliseconds 250
    }
    function Open-131Main {
        $null=Invoke-131Adb @('shell','am','start','--activity-clear-top','-n',($package+'/com.tsd.czsetcollector.MainActivity'))
        Start-Sleep -Milliseconds 350
        $ui=Read-131Ui;Assert-131Screen $ui
        $versions=@(Find-131Node $ui 'tvAppVersion')
        if($versions.Count -ne 1 -or $versions[0].GetAttribute('text') -notmatch '^1\.3\.1(?:\s|$)'){throw 'Known 1.3.1 app screen did not open'}
        return $ui
    }
    function Assert-131Chooser {
        # Inspect only: never choose a recipient, press Back, or tap another app.
        $null=Read-131Ui
        $r=Invoke-131Adb @('shell','dumpsys','activity','activities')
        if($r.Text -notmatch '(?:mResumedActivity|topResumedActivity)[^\r\n]*(?:android|com\.android\.intentresolver)/[^\s}]*ChooserActivity') {
            throw 'Verified export did not open the Android share chooser; stale files cannot authorize an update'
        }
    }
    function Get-131ByteHash([byte[]]$Bytes) {
        $sha=[Security.Cryptography.SHA256]::Create()
        try{return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-','').ToLowerInvariant()}finally{$sha.Dispose()}
    }
    function Read-131Export([string]$Name,[bool]$MayBeMissing=$false) {
        $path=$remote+$Name
        $stat=Invoke-ChzNative $adb @('-s',$serial,'shell','stat','-c','%Y:%s',$path) 20000
        if($stat.ExitCode -ne 0) {
            if($MayBeMissing -and ($stat.Text+$stat.ErrorText) -match 'No such file or directory'){return $null}
            throw 'Cannot inspect exported backup file on TSD'
        }
        $m=[regex]::Match($stat.Text.Trim(),'^(\d+):(\d+)$')
        if(!$m.Success -or [long]$m.Groups[2].Value -le 0 -or [long]$m.Groups[2].Value -gt 16MB){throw 'Exported backup is missing, empty, or too large'}
        $read=Invoke-131Adb @('shell','cat',$path)
        if($read.Bytes.Length -ne [long]$m.Groups[2].Value){throw 'Exported file changed while reading'}
        return [pscustomobject]@{Mtime=[long]$m.Groups[1].Value;Size=[long]$m.Groups[2].Value;FileSha256=(Get-131ByteHash $read.Bytes)}
    }

    $beforeProfiles=Read-131Export $names[0] $true
    $beforeQueue=Read-131Export $names[1] $true
    $ui=Open-131Main
    if(@(Find-131Node $ui 'workPage').Count -eq 1) {
        $null=Seek-131Node 'tvCurrentSet' 'workPage'
        $null=Seek-131Node 'btnBackProfiles' 'workPage' 'up'
        Tap-131Id 'btnBackProfiles' 'Выйти к выбору профиля'
        Tap-131Dialog 'Выйти к выбору профиля?' 'К ПРОФИЛЯМ'
    } elseif(@(Find-131Node $ui 'profilePage').Count -ne 1){throw 'Unknown app page; no backup actions performed'}
    # Exact Start text also excludes a draft editor that is currently below the visible area.
    $start=Seek-131Node 'btnStartWork' 'profilePage' 'up'
    if($start.GetAttribute('text') -ne 'Начать работу'){throw 'Unsaved profile editor detected; backup stopped'}
    $null=Seek-131Node 'btnExportProfiles' 'profilePage'
    Tap-131Id 'btnExportProfiles' 'Экспорт всех профилей и шаблонов'
    Assert-131Chooser
    $null=Open-131Main
    $null=Seek-131Node 'btnStartWork' 'profilePage' 'up'
    Tap-131Id 'btnStartWork' 'Начать работу'
    $null=Seek-131Node 'tvCurrentSet' 'workPage'
    $null=Seek-131Node 'btnBackup' 'workPage'
    Tap-131Id 'btnBackup' 'Экспорт резервной копии очереди'
    Tap-131Dialog 'Экспорт резервной копии' 'ЭКСПОРТИРОВАТЬ'
    Assert-131Chooser
    $null=Open-131Main

    $checks=@()
    for($i=0;$i -lt $names.Count;$i++) {
        $name=$names[$i];$before=if($i -eq 0){$beforeProfiles}else{$beforeQueue}
        $first=Read-131Export $name
        $local=Join-Path $destination $name
        $null=Invoke-131Adb @('pull',($remote+$name),$local)
        $format=if($i -eq 0){'cz-profiles-backup'}else{'cz-queue-backup'}
        $payload=Assert-ChzEnvelope $local $format
        $second=Read-131Export $name
        $fileHash=(Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash.ToLowerInvariant()
        if($first.Mtime -ne $second.Mtime -or $first.Size -ne $second.Size -or $first.FileSha256 -ne $second.FileSha256 -or $fileHash -ne $second.FileSha256){throw 'Backup file changed during verification; no update permitted'}
        if($before -and ($second.Mtime -lt $before.Mtime -or ($second.Mtime -eq $before.Mtime -and $second.FileSha256 -ne $before.FileSha256))){throw 'Fresh export cannot be confirmed; no update permitted'}
        $freshness=if(!$before){'created-after-observed-export'}elseif($second.Mtime -gt $before.Mtime){'newer-mtime'}else{'identical-file-after-observed-export-and-chooser'}
        $checks+= [pscustomobject]@{Name=$name;Payload=$payload;Sha256=(Get-ChzHash $payload);Mtime=$second.Mtime;PreviousMtime=$(if($before){$before.Mtime}else{$null});Freshness=$freshness;FileSha256=$fileHash}
    }
    $p=$checks[0].Payload|ConvertFrom-Json;$q=$checks[1].Payload|ConvertFrom-Json
    if($p.profiles -isnot [array] -or $q.jobs -isnot [array] -or @($p.profiles).Count -eq 0){throw 'Backup structure or profile list is invalid'}
    if($null -ne $q.current){throw 'An unfinished set appeared during backup. It remains preserved; update blocked.'}
    if($p.selectedIndex -notmatch '^\d+$' -or [long]$p.selectedIndex -ge @($p.profiles).Count){throw 'Selected profile in backup is invalid'}
    return [pscustomobject]@{
        verified=$true;profilesSha256=$checks[0].Sha256;queueSha256=$checks[1].Sha256
        profileCount=@($p.profiles).Count;jobCount=@($q.jobs).Count;hasCurrent=$false
        source='observed-1.3.1-export-buttons';exports=@($checks|Select-Object Name,Mtime,PreviousMtime,Freshness,FileSha256)
    }
}
