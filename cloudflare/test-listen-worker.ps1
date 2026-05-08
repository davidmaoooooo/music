param(
    [string]$BaseUrl = "https://ncm.mjz0227.ccwu.cc",
    [string]$Room = ""
)

$ErrorActionPreference = "Stop"

function Now-Ms {
    return [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
}

function Convert-ToWebSocketUrl([string]$Url) {
    $trimmed = $Url.Trim().TrimEnd("/")
    if (-not ($trimmed.StartsWith("http://") -or $trimmed.StartsWith("https://") -or $trimmed.StartsWith("ws://") -or $trimmed.StartsWith("wss://"))) {
        $trimmed = "https://$trimmed"
    }
    if ($trimmed.StartsWith("https://")) {
        $trimmed = "wss://" + $trimmed.Substring("https://".Length)
    } elseif ($trimmed.StartsWith("http://")) {
        $trimmed = "ws://" + $trimmed.Substring("http://".Length)
    }
    if (-not $trimmed.EndsWith("/ws")) {
        $trimmed = "$trimmed/ws"
    }
    return $trimmed
}

function New-WsClient([string]$Url) {
    $client = [System.Net.WebSockets.ClientWebSocket]::new()
    $client.Options.KeepAliveInterval = [TimeSpan]::FromSeconds(20)
    $cts = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(15))
    [void]$client.ConnectAsync([Uri]$Url, $cts.Token).GetAwaiter().GetResult()
    return $client
}

function Send-WsJson($Client, $Object) {
    $json = $Object | ConvertTo-Json -Depth 30 -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $segment = [ArraySegment[byte]]::new($bytes)
    $cts = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(10))
    [void]$Client.SendAsync($segment, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $cts.Token).GetAwaiter().GetResult()
    Write-Host "SEND: $json"
}

function Receive-WsText($Client, [int]$TimeoutSeconds = 10) {
    $buffer = [byte[]]::new(32768)
    $stream = [System.IO.MemoryStream]::new()
    do {
        $segment = [ArraySegment[byte]]::new($buffer)
        $task = $Client.ReceiveAsync($segment, [System.Threading.CancellationToken]::None)
        if (-not $task.Wait([TimeSpan]::FromSeconds($TimeoutSeconds))) {
            throw "Receive timeout"
        }
        $result = $task.GetAwaiter().GetResult()
        if ($result.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
            throw "WebSocket closed: $($Client.CloseStatus) $($Client.CloseStatusDescription)"
        }
        $stream.Write($buffer, 0, $result.Count)
    } while (-not $result.EndOfMessage)
    $text = [System.Text.Encoding]::UTF8.GetString($stream.ToArray())
    Write-Host "RECV: $text"
    return $text
}

function Close-Ws($Client) {
    if (-not $Client) {
        return
    }
    try {
        if ($Client.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
            $cts = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(5))
            [void]$Client.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, "test done", $cts.Token).GetAwaiter().GetResult()
        }
    } catch {
    } finally {
        $Client.Dispose()
    }
}

function New-Song([int]$Id, [string]$Title) {
    return @{
        mediaId = "1#$Id"
        uri = "netease://com.david.music?id=$Id"
        title = $Title
        artist = "Test Artist"
        album = "Test Album"
        cover = ""
        duration = 180000
        songId = $Id
    }
}

function Assert-MessageType($Text, [string]$Type) {
    $json = $Text | ConvertFrom-Json
    if ($json.type -ne $Type) {
        throw "Expected $Type but received $($json.type): $Text"
    }
    return $json
}

$httpBase = $BaseUrl.TrimEnd("/")
$wsBase = Convert-ToWebSocketUrl $BaseUrl
if ([string]::IsNullOrWhiteSpace($Room)) {
    $Room = (Get-Random -Minimum 100000 -Maximum 999999).ToString()
}

$ownerId = "test-owner-$([Guid]::NewGuid().ToString("N"))"
$guestId = "test-guest-$([Guid]::NewGuid().ToString("N"))"
$ownerUrl = "${wsBase}?room=$Room&clientId=$ownerId&deviceName=PowerShellOwner&mode=create"
$guestUrl = "${wsBase}?room=$Room&clientId=$guestId&deviceName=PowerShellGuest&mode=join"
$playlist = @(
    (New-Song 100001 "Test Song 1"),
    (New-Song 100002 "Test Song 2"),
    (New-Song 100003 "Test Song 3")
)

Write-Host "HTTP test: $httpBase"
$root = Invoke-WebRequest -Uri $httpBase -UseBasicParsing -TimeoutSec 15
Write-Host "HTTP $($root.StatusCode): $($root.Content)"
Write-Host "Room: $Room"

$owner = $null
$guest = $null
try {
    $owner = New-WsClient $ownerUrl
    Assert-MessageType (Receive-WsText $owner) "server_ready" | Out-Null

    $guest = New-WsClient $guestUrl
    Assert-MessageType (Receive-WsText $guest) "server_ready" | Out-Null
    Assert-MessageType (Receive-WsText $owner) "peer_joined" | Out-Null

    Send-WsJson $owner @{
        type = "owner_cookie"
        clientVersion = 1
        clientSentAt = Now-Ms
        payload = @{ cookie = "MUSIC_U=test-owner-cookie; NMTID=test-nmtid" }
    }
    Assert-MessageType (Receive-WsText $guest) "owner_cookie" | Out-Null
    Assert-MessageType (Receive-WsText $owner) "event_ack" | Out-Null

    Send-WsJson $owner @{
        type = "playlist"
        clientVersion = 2
        clientSentAt = Now-Ms
        payload = @{
            playlist = $playlist
            currentMediaId = $playlist[0].mediaId
            currentIndex = 0
            playing = $true
        }
    }
    Assert-MessageType (Receive-WsText $guest) "playlist" | Out-Null
    Assert-MessageType (Receive-WsText $owner) "event_ack" | Out-Null

    Send-WsJson $guest @{
        type = "current_song"
        clientVersion = 3
        clientSentAt = Now-Ms
        payload = @{
            song = $playlist[1]
            mediaId = $playlist[1].mediaId
            index = 1
            playing = $true
            positionMs = 0
        }
    }
    Assert-MessageType (Receive-WsText $owner) "current_song" | Out-Null
    Assert-MessageType (Receive-WsText $guest) "event_ack" | Out-Null

    Send-WsJson $owner @{
        type = "play_state"
        clientVersion = 4
        clientSentAt = Now-Ms
        payload = @{ playing = $false; positionMs = 23000 }
    }
    Assert-MessageType (Receive-WsText $guest) "play_state" | Out-Null
    Assert-MessageType (Receive-WsText $owner) "event_ack" | Out-Null

    Send-WsJson $guest @{
        type = "seek"
        clientVersion = 5
        clientSentAt = Now-Ms
        payload = @{ positionMs = 46000 }
    }
    Assert-MessageType (Receive-WsText $owner) "seek" | Out-Null
    Assert-MessageType (Receive-WsText $guest) "event_ack" | Out-Null

    Send-WsJson $owner @{ type = "ping"; clientSentAt = Now-Ms }
    Assert-MessageType (Receive-WsText $owner) "pong" | Out-Null

    Send-WsJson $guest @{ type = "leave"; clientSentAt = Now-Ms }
    Assert-MessageType (Receive-WsText $owner) "peer_left" | Out-Null
} finally {
    Close-Ws $owner
    Close-Ws $guest
}

Write-Host "OK: Worker event protocol room flow passed."
