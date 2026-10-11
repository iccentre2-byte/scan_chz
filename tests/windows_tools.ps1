param([string]$Python='python.exe')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '..\tools\ToolCommon.ps1')
$pythonExe=(Get-Command $Python).Source
$unicode=-join ([char[]](0x41f,0x440,0x43e,0x444,0x438,0x43b,0x44c))
foreach($value in @('C:\Test Folder\file.json','C:\Test Folder\trailing\','a"b',$unicode)) {
    $r=Invoke-ChzNative $pythonExe @('-c','import sys;sys.stdout.buffer.write(sys.argv[1].encode("utf-8"))',$value)
    if($r.ExitCode -ne 0 -or $r.Text -cne $value){throw 'Native argument/UTF8 preservation failed'}
}
$hash=Get-ChzHash $unicode
$payload=([ordered]@{profiles=@();selectedIndex=0}|ConvertTo-Json -Compress)
$temp=Join-Path ([IO.Path]::GetTempPath()) ('chz-backup-test-'+[guid]::NewGuid().ToString('N')+'.json')
try {
    Write-ChzUtf8 $temp (New-ChzEnvelope 'cz-profiles-backup' $payload)
    if((Assert-ChzEnvelope $temp 'cz-profiles-backup') -cne $payload){throw 'Envelope roundtrip failed'}
    $bad=(New-ChzEnvelope 'cz-profiles-backup' $payload)|ConvertFrom-Json
    $bad.sha256='0'*64;Write-ChzUtf8 $temp ($bad|ConvertTo-Json -Compress)
    $rejected=$false
    try{Assert-ChzEnvelope $temp 'cz-profiles-backup'|Out-Null}catch{$rejected=$true}
    if(!$rejected){throw 'Corrupted backup accepted'}
} finally {if(Test-Path -LiteralPath $temp){Remove-Item -LiteralPath $temp}}
Write-Host 'Windows tools checks passed: native quoting, UTF8, envelope and corruption rejection.'
$a='{"profiles":[{"title":"test","template":{"expected":{"04607010740016":1},"setGtin":"04609144660000"}}],"selectedIndex":0}'|ConvertFrom-Json
$b='{"selectedIndex":0,"profiles":[{"template":{"setGtin":"04609144660000","expected":{"04607010740016":1}},"title":"test"}]}'|ConvertFrom-Json
if((Get-ChzCanonicalJson $a) -cne (Get-ChzCanonicalJson $b)){throw 'Field ordering must not change semantic backup comparison'}
$b.profiles[0].template.expected.'04607010740016'=2
if((Get-ChzCanonicalJson $a) -ceq (Get-ChzCanonicalJson $b)){throw 'Changed composition must fail preservation check'}
Write-Host 'Backup preservation comparison passed: field ordering ignored; changed composition rejected.'
