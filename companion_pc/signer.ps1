# CHZ PC helper hotfix JS1 (2026-10-11).
# PowerShell is a launcher/JSON adapter only. ALL CAdESCOM calls run in JScript.
# Compatible with the existing signer.py and PyInstaller command: one file to replace.
# Private keys/PIN are never exported. No network calls here.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$work = $null
$p = $null
$rc = 1
$stage = 'request validation'
try {
    $raw = [Console]::In.ReadToEnd()
    if ($raw.Length -gt 3000000) { throw 'Request too large' }
    $req = $raw | ConvertFrom-Json
    $request = $null
    if ($req.action -ceq 'certificates') {
        $request = @{ action = 'certificates' }
    } elseif ($req.action -ceq 'sign') {
        $thumb = ([string]$req.thumbprint -replace '\s','').ToUpperInvariant()
        if ($thumb -notmatch '^[0-9A-F]{40}$') { throw 'Invalid certificate thumbprint' }
        if ($req.detached -isnot [bool]) { throw 'detached must be boolean' }
        $body = [string]$req.contentBase64
        if (-not $body -or $body.Length -gt 2800000 -or $body -cnotmatch '^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$') {
            throw 'Invalid contentBase64'
        }
        $bytes = [Convert]::FromBase64String($body)
        if ([Convert]::ToBase64String($bytes) -cne $body) { throw 'Non-canonical contentBase64' }
        $bytes = $null
        $request = @{ action = 'sign'; thumbprint = $thumb; contentBase64 = $body; detached = [bool]$req.detached }
    } else { throw 'Unsupported signing action' }

    $token = [Guid]::NewGuid().ToString('N')
    $work = Join-Path ([IO.Path]::GetTempPath()) ('CHZ_signer_' + $token)
    [void][IO.Directory]::CreateDirectory($work)
    $scriptFile = Join-Path $work 'bridge.js'
    $reportFile = Join-Path $work 'response.json'

    # Only the validated ASCII action/hex/base64/bool fields enter this literal.
    # No eval(), no dynamic code from subjects, certificate data, or HTTP input.
    $javascript = @'
(function () {
    var req = __CHZ_VALIDATED_REQUEST__;
    var fs = null, report = '', token = '', stream = null;
    var store = null, opened = false, stage = 'startup';
    var response = null, rc = 1;
    function quote(value) {
        return '"' + String(value).replace(/[\\"\x00-\x1f\u2028\u2029]/g, function (c) {
            if (c === '"') return '\\"';
            if (c === '\\') return '\\\\';
            return '\\u' + ('0000' + c.charCodeAt(0).toString(16)).slice(-4);
        }) + '"';
    }
    function json(value) {
        if (value === null) return 'null';
        if (typeof value === 'string') return quote(value);
        if (typeof value === 'boolean') return value ? 'true' : 'false';
        if (typeof value === 'number' && isFinite(value)) return String(value);
        var out = [], i, k;
        if (Object.prototype.toString.call(value) === '[object Array]') {
            for (i = 0; i < value.length; i++) out.push(json(value[i]));
            return '[' + out.join(',') + ']';
        }
        if (typeof value === 'object') {
            for (k in value) if (Object.prototype.hasOwnProperty.call(value, k)) out.push(quote(k) + ':' + json(value[k]));
            return '{' + out.join(',') + '}';
        }
        throw new Error('Unsupported JSON value');
    }
    function writeResult(obj) {
        stream = fs.CreateTextFile(report, true, true); // UTF-16, no stdout pipe.
        try { stream.Write(json(obj)); } finally { stream.Close(); stream = null; }
    }
    function failure(e) {
        var code = typeof e.number === 'number' ? '0x' + ('00000000' + (e.number >>> 0).toString(16).toUpperCase()).slice(-8) : '';
        return 'CAdESCOM [' + stage + ']' + (code ? ' ' + code : '') + ': ' + String(e.description || e.message || 'Unknown error');
    }
    function dateOf(value) {
        var d = new Date(value);
        if (!isFinite(d.getTime())) throw new Error('Invalid certificate date');
        return d;
    }
    function dateText(d) {
        function z(n) { return (n < 10 ? '0' : '') + n; }
        return d.getFullYear() + '-' + z(d.getMonth()+1) + '-' + z(d.getDate()) + 'T' + z(d.getHours()) + ':' + z(d.getMinutes()) + ':' + z(d.getSeconds());
    }
    function validNow(c, now) {
        return dateOf(c.ValidFromDate).getTime() <= now && dateOf(c.ValidToDate).getTime() > now;
    }
    try {
        if (WScript.Arguments.length !== 2) throw new Error('Expected report path and run token');
        report = String(WScript.Arguments.Item(0));
        token = String(WScript.Arguments.Item(1));
        if (!/^[a-f0-9]{32}$/.test(token)) throw new Error('Invalid run token');
        fs = new ActiveXObject('Scripting.FileSystemObject');
        writeResult({runToken:token, completed:false, error:'JScript started but not completed'});
        stage = 'Create CAdESCOM.Store';
        store = new ActiveXObject('CAdESCOM.Store');
        stage = 'Store.Open(2, My, 0)';
        store.Open(2, 'My', 0);
        opened = true;
        stage = 'Store.Certificates';
        var certs = store.Certificates;
        var count = Number(certs.Count);
        if (!isFinite(count) || count < 0 || Math.floor(count) !== count || count > 10000) throw new Error('Invalid or excessive certificate count');
        var now = new Date().getTime();
        if (req.action === 'certificates') {
            var items = [], warnings = [], seen = {};
            for (var n=1; n<=count; n++) {
                try {
                    stage = 'Certificate #' + n + ': metadata';
                    var c = certs.Item(n);
                    if (!c.HasPrivateKey() || !validNow(c, now)) continue;
                    var thumb = String(c.Thumbprint).replace(/\s/g, '').toUpperCase();
                    if (!/^[0-9A-F]{40}$/.test(thumb)) throw new Error('Invalid thumbprint');
                    if (seen[thumb]) continue;
                    seen[thumb] = true;
                    items.push({thumbprint:thumb, subject:String(c.SubjectName), issuer:String(c.IssuerName), validTo:dateText(dateOf(c.ValidToDate))});
                } catch (certError) { warnings.push(failure(certError)); }
            }
            if (!items.length && warnings.length) throw new Error(warnings.join('; '));
            response = {certificates:items, warnings:warnings, bridge:'JScript-direct-file-JS1'};
        } else if (req.action === 'sign') {
            // No retries, no automatic certificate choice, and no signature during listing.
            if (!/^[0-9A-F]{40}$/.test(req.thumbprint) || typeof req.detached !== 'boolean') throw new Error('Invalid signing request');
            if (!req.contentBase64 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(req.contentBase64)) throw new Error('Invalid base64 content');
            stage = 'Certificates.Find(SHA1)';
            var matches = certs.Find(0, req.thumbprint);
            if (Number(matches.Count) !== 1) throw new Error('Selected certificate not found uniquely in CurrentUser/My');
            var cert = matches.Item(1);
            if (String(cert.Thumbprint).replace(/\s/g, '').toUpperCase() !== req.thumbprint) throw new Error('Certificate thumbprint mismatch');
            stage = 'Selected certificate: key and dates';
            if (!cert.HasPrivateKey()) throw new Error('Selected certificate has no linked private key');
            if (!validNow(cert, now)) throw new Error('Selected certificate is expired or not yet valid');
            stage = 'CPSigner';
            var signer = new ActiveXObject('CAdESCOM.CPSigner');
            signer.Certificate = cert;
            signer.CheckCertificate = true;
            stage = 'CadesSignedData.Content';
            var data = new ActiveXObject('CAdESCOM.CadesSignedData');
            data.ContentEncoding = 1; // BASE64_TO_BINARY; MUST be set before Content.
            data.Content = req.contentBase64;
            stage = 'SignCades (PIN window may be open)';
            var signature = String(data.SignCades(signer, 1, req.detached));
            if (!signature.replace(/\s/g, '')) throw new Error('Empty signature');
            stage = 'VerifyCades';
            var check = new ActiveXObject('CAdESCOM.CadesSignedData');
            check.ContentEncoding = 1;
            check.Content = req.contentBase64;
            check.VerifyCades(signature, 1, req.detached);
            response = {signature:signature.replace(/\s/g, ''), bridge:'JScript-direct-file-JS1'};
        } else throw new Error('Unsupported action');
        rc = 0;
    } catch (e) {
        response = {error:failure(e), bridge:'JScript-direct-file-JS1'};
        rc = 1;
    } finally {
        if (store && opened) { try { store.Close(); } catch (ignoreClose) {} }
        store = null;
    }
    try {
        if (!fs || !report) { WScript.Quit(2); return; }
        response.runToken = token;
        response.completed = true;
        writeResult(response);
    } catch (writeError) { rc = 2; }
    WScript.Quit(rc);
}());
'@
    $javascript = $javascript.Replace('__CHZ_VALIDATED_REQUEST__', ($request | ConvertTo-Json -Compress -Depth 4))
    [IO.File]::WriteAllText($scriptFile, $javascript, [Text.Encoding]::ASCII)
    $directory = 'System32'
    if ([Environment]::Is64BitOperatingSystem -and -not [Environment]::Is64BitProcess) { $directory = 'Sysnative' }
    $exe = Join-Path $env:SystemRoot ($directory + '\cscript.exe')
    if (-not [IO.File]::Exists($exe)) { throw 'Windows cscript.exe not found' }
    $stage = 'launch cscript/JScript'
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $exe
    $psi.Arguments = '//nologo //E:JScript //U //T:150 "' + $scriptFile + '" "' + $reportFile + '" "' + $token + '"'
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $false
    # Use the same direct-file path that succeeded on the user's PC. Do not read
    # stdout from cscript; its exit code alone is NOT evidence of a completed call.
    $psi.RedirectStandardOutput = $false
    $psi.RedirectStandardError = $false
    $p = New-Object System.Diagnostics.Process
    $p.StartInfo = $psi
    if (-not $p.Start()) { throw 'cscript failed to start' }
    if (-not $p.WaitForExit(155000)) {
        try { $p.Kill(); [void]$p.WaitForExit(2000) } catch {}
        throw 'CryptoPro timed out. Check the PIN window. No document was submitted by this signer.'
    }
    $stage = 'read verified JScript response'
    if (-not [IO.File]::Exists($reportFile)) { throw 'JScript produced no response file; host exit code alone is not success' }
    if ((Get-Item -LiteralPath $reportFile).Length -gt 12000000) { throw 'JScript response too large' }
    $response = [IO.File]::ReadAllText($reportFile, [Text.Encoding]::Unicode) | ConvertFrom-Json
    if ($response.runToken -cne $token -or $response.completed -ne $true) { throw 'JScript response is incomplete or belongs to another call' }
    if ($response.error) { throw ([string]$response.error) }
    if ($p.ExitCode -ne 0) { throw ('JScript host exited with code ' + $p.ExitCode) }
    if ($request.action -eq 'certificates') {
        if ($null -eq $response.PSObject.Properties['certificates'] -or $response.certificates -isnot [array]) { throw 'JScript certificate list is missing or invalid' }
        $reply = @{ certificates = @($response.certificates); warnings = @($response.warnings); bridge = 'JScript-direct-file-JS1' }
    } else {
        $signature = [string]$response.signature
        if (-not $signature -or $signature -cnotmatch '^[A-Za-z0-9+/]+={0,2}$') { throw 'JScript did not return a base64 signature' }
        [void][Convert]::FromBase64String($signature)
        $reply = @{ signature = $signature }
    }
    [Console]::Out.WriteLine(($reply | ConvertTo-Json -Compress -Depth 8))
    $rc = 0
} catch {
    $reply = @{ error = ('CHZ JS1 [' + $stage + ']: ' + $_.Exception.Message) }
    [Console]::Out.WriteLine(($reply | ConvertTo-Json -Compress))
} finally {
    if ($null -ne $p) { try { $p.Dispose() } catch {} }
    # Remove only this call's temporary files, never the app data/queue or certificates.
    if ($work -and [IO.Directory]::Exists($work)) {
        try { [IO.Directory]::Delete($work, $true) } catch {}
    }
}
exit $rc
