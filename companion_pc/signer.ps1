# Current-user certificate store only. Never exports private keys or caches a PIN.
# CAdESCOM API: docs.cryptopro.ru/cades/reference/cadescom
$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$store = $null
try {
    $req = [Console]::In.ReadToEnd() | ConvertFrom-Json
    $store = New-Object -ComObject CAdESCOM.Store
    $store.Open(2, 'My', 0) # CAPICOM_CURRENT_USER_STORE, MY, read-only
    if ($req.action -eq 'certificates') {
        $items = @()
        for ($n=1; $n -le $store.Certificates.Count; $n++) {
            $c = $store.Certificates.Item($n)
            if ($c.HasPrivateKey() -and [datetime]$c.ValidToDate -gt (Get-Date) -and [datetime]$c.ValidFromDate -le (Get-Date)) {
                $items += @{ thumbprint=[string]$c.Thumbprint; subject=[string]$c.SubjectName;
                    issuer=[string]$c.IssuerName; validTo=([datetime]$c.ValidToDate).ToString('s') }
            }
        }
        @{ certificates=@($items) } | ConvertTo-Json -Depth 5 -Compress
    } elseif ($req.action -eq 'sign') {
        $thumb = ([string]$req.thumbprint -replace '\s','').ToUpperInvariant()
        if ($thumb -notmatch '^[0-9A-F]{40}$') { throw 'Invalid certificate thumbprint' }
        $matches = $store.Certificates.Find(0, $thumb) # SHA1_HASH
        if ($matches.Count -ne 1) { throw 'Signing certificate not found in CurrentUser\My' }
        $cert = $matches.Item(1)
        if (-not $cert.HasPrivateKey()) { throw 'Certificate has no private key' }
        if ([datetime]$cert.ValidToDate -le (Get-Date)) { throw 'Signing certificate expired' }
        $signer = New-Object -ComObject CAdESCOM.CPSigner
        $signer.Certificate = $cert
        $signer.CheckCertificate = $true
        $data = New-Object -ComObject CAdESCOM.CadesSignedData
        $data.ContentEncoding = 1 # CADESCOM_BASE64_TO_BINARY, set BEFORE Content
        $data.Content = [string]$req.contentBase64
        $detached = [bool]$req.detached
        $signature = $data.SignCades($signer, 1, $detached) # CADESCOM_CADES_BES
        # Verify the generated CMS before returning it to the API client.
        $data.VerifyCades($signature, 1, $detached)
        @{ signature=([string]$signature -replace '\s','') } | ConvertTo-Json -Compress
    } else { throw 'Unsupported signing action' }
} catch {
    @{ error=$_.Exception.Message } | ConvertTo-Json -Compress
    exit 1
} finally {
    if ($null -ne $store) { try { $store.Close() } catch {} }
}
