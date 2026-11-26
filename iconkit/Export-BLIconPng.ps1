<#
.SYNOPSIS
  Renders a project's BL icon (the 3 Android layers) to a PNG, for places that need a bitmap:
  Play Store (512, square), Tizen watch (round), README...

.DESCRIPTION
  Converts the Android vector drawables to SVG, then screenshots it with headless Edge.
  Only the visible part of the adaptive icon is rendered (the central 72 of the 108 viewport).

  -Shape square   : full square, no mask (Play Store applies its own)
  -Shape round    : circle (Galaxy Watch / Tizen)
  -Shape squircle : rounded square (README, web)

  -Project defaults to the project containing this iconkit folder.

.EXAMPLE
  .\iconkit\Export-BLIconPng.ps1 -Size 512 -Shape square -Out icon-512.png
#>
param(
    [string] $Project = (Split-Path $PSScriptRoot),
    [Parameter(Mandatory)] [string] $Out,
    [int] $Size = 512,
    [ValidateSet('square', 'round', 'squircle')] [string] $Shape = 'square',
    [string] $Browser = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
)
$ErrorActionPreference = 'Stop'
$res = Join-Path $Project 'app\src\main\res'
$ns = 'http://schemas.android.com/apk/res/android'
$inv = [Globalization.CultureInfo]::InvariantCulture

# ---------- colors

$colors = @{}
Get-ChildItem (Join-Path $res 'values') -Filter *.xml | ForEach-Object {
    $x = [xml](Get-Content $_.FullName -Raw -Encoding UTF8)
    foreach ($c in $x.SelectNodes('/resources/color')) { $colors[$c.name] = $c.InnerText.Trim() }
}

function Resolve-Color([string] $v) {
    if (-not $v) { return $null }
    while ($v.StartsWith('@color/')) {
        $name = $v.Substring(7)
        if (-not $colors.ContainsKey($name)) { throw "Unknown color resource $v" }
        $v = $colors[$name]
    }
    $h = $v.TrimStart('#')
    switch ($h.Length) {
        3 { $a = 255; $r = [Convert]::ToInt32("$($h[0])$($h[0])", 16); $g = [Convert]::ToInt32("$($h[1])$($h[1])", 16); $b = [Convert]::ToInt32("$($h[2])$($h[2])", 16) }
        4 { $a = [Convert]::ToInt32("$($h[0])$($h[0])", 16); $r = [Convert]::ToInt32("$($h[1])$($h[1])", 16); $g = [Convert]::ToInt32("$($h[2])$($h[2])", 16); $b = [Convert]::ToInt32("$($h[3])$($h[3])", 16) }
        6 { $a = 255; $r = [Convert]::ToInt32($h.Substring(0, 2), 16); $g = [Convert]::ToInt32($h.Substring(2, 2), 16); $b = [Convert]::ToInt32($h.Substring(4, 2), 16) }
        8 { $a = [Convert]::ToInt32($h.Substring(0, 2), 16); $r = [Convert]::ToInt32($h.Substring(2, 2), 16); $g = [Convert]::ToInt32($h.Substring(4, 2), 16); $b = [Convert]::ToInt32($h.Substring(6, 2), 16) }
        default { throw "Bad color $v" }
    }
    return @{ rgb = "rgb($r,$g,$b)"; alpha = $a / 255.0 }
}

function Num([double] $d) { $d.ToString('0.####', $inv) }

# ---------- vector drawable -> svg

function Convert-Node($node) {
    $sb = New-Object System.Text.StringBuilder
    foreach ($child in $node.ChildNodes) {
        if ($child.NodeType -ne 'Element') { continue }
        $a = { param($n) $child.GetAttribute($n, $ns) }
        if ($child.LocalName -eq 'group') {
            $tx = & $a 'translateX'; $ty = & $a 'translateY'
            $sx = & $a 'scaleX'; $sy = & $a 'scaleY'
            $rot = & $a 'rotation'; $px = & $a 'pivotX'; $py = & $a 'pivotY'
            if (-not $px) { $px = 0 }; if (-not $py) { $py = 0 }
            if (-not $sx) { $sx = 1 }; if (-not $sy) { $sy = 1 }
            if (-not $tx) { $tx = 0 }; if (-not $ty) { $ty = 0 }
            if (-not $rot) { $rot = 0 }
            # Android: translate(t+pivot) rotate scale translate(-pivot)
            $t = "translate($([double]$tx + [double]$px) $([double]$ty + [double]$py)) rotate($rot) scale($sx $sy) translate($(-[double]$px) $(-[double]$py))"
            [void]$sb.Append("<g transform=`"$t`">").Append((Convert-Node $child)).Append('</g>')
        }
        elseif ($child.LocalName -eq 'path') {
            $attrs = @("d=`"$(& $a 'pathData')`"")
            $fill = Resolve-Color (& $a 'fillColor')
            if ($fill) {
                $fa = & $a 'fillAlpha'; $op = $fill.alpha * $(if ($fa) { [double]::Parse($fa, $inv) } else { 1 })
                $attrs += "fill=`"$($fill.rgb)`"", "fill-opacity=`"$(Num $op)`""
                if ((& $a 'fillType') -eq 'evenOdd') { $attrs += 'fill-rule="evenodd"' }
            } else { $attrs += 'fill="none"' }
            $stroke = Resolve-Color (& $a 'strokeColor')
            if ($stroke) {
                $sa = & $a 'strokeAlpha'; $op = $stroke.alpha * $(if ($sa) { [double]::Parse($sa, $inv) } else { 1 })
                $sw = & $a 'strokeWidth'; if (-not $sw) { $sw = '0' }
                $cap = & $a 'strokeLineCap'; if (-not $cap) { $cap = 'butt' }
                $join = & $a 'strokeLineJoin'; if (-not $join) { $join = 'miter' }
                $attrs += "stroke=`"$($stroke.rgb)`"", "stroke-opacity=`"$(Num $op)`"", "stroke-width=`"$sw`"",
                    "stroke-linecap=`"$cap`"", "stroke-linejoin=`"$join`""
            }
            [void]$sb.Append('<path ').Append($attrs -join ' ').Append('/>')
        }
    }
    $sb.ToString()
}

function Convert-Vector([string] $name) {
    $file = Join-Path $res "drawable\$name.xml"
    $x = [xml](Get-Content $file -Raw -Encoding UTF8)
    $v = $x.DocumentElement
    if ($v.LocalName -ne 'vector') { throw "$name is not a <vector>" }
    $vw = [double]::Parse($v.GetAttribute('viewportWidth', $ns), $inv)
    $vh = [double]::Parse($v.GetAttribute('viewportHeight', $ns), $inv)
    $body = Convert-Node $v
    # Scale the drawable's viewport to the 108x108 icon space
    if ($vw -ne 108 -or $vh -ne 108) { $body = "<g transform=`"scale($(Num (108 / $vw)) $(Num (108 / $vh)))`">$body</g>" }
    $body
}

# ---------- background gradient (same corner mapping as Android GradientDrawable)

$bg = [xml](Get-Content (Join-Path $res 'drawable\ic_launcher_bg.xml') -Raw -Encoding UTF8)
$grad = $bg.SelectSingleNode('//gradient')
$angle = [int]$grad.GetAttribute('angle', $ns) % 360
$start = Resolve-Color $grad.GetAttribute('startColor', $ns)
$end = Resolve-Color $grad.GetAttribute('endColor', $ns)
$dir = @{ 0 = '0 0 108 0'; 45 = '0 108 108 0'; 90 = '0 108 0 0'; 135 = '108 108 0 0';
    180 = '108 0 0 0'; 225 = '108 0 0 108'; 270 = '0 0 0 108'; 315 = '0 0 108 108' }[$angle]
if (-not $dir) { throw "Gradient angle $angle not supported (multiples of 45 only)" }
$x1, $y1, $x2, $y2 = $dir.Split(' ')

$clip = switch ($Shape) {
    'square' { '' }
    'round' { '<clipPath id="m"><circle cx="54" cy="54" r="36"/></clipPath>' }
    'squircle' { '<clipPath id="m"><rect x="18" y="18" width="72" height="72" rx="20" ry="20"/></clipPath>' }
}
$clipAttr = if ($clip) { ' clip-path="url(#m)"' } else { '' }
# In a circle the corner arrows would touch the edge: shrink arrows + center (not the background)
$inner = if ($Shape -eq 'round') { 0.88 } else { 1.0 }

$svg = @"
<svg xmlns="http://www.w3.org/2000/svg" width="$Size" height="$Size" viewBox="18 18 72 72">
<defs>
<linearGradient id="bg" gradientUnits="userSpaceOnUse" x1="$x1" y1="$y1" x2="$x2" y2="$y2">
<stop offset="0" stop-color="$($start.rgb)" stop-opacity="$(Num $start.alpha)"/>
<stop offset="1" stop-color="$($end.rgb)" stop-opacity="$(Num $end.alpha)"/>
</linearGradient>
$clip
</defs>
<g$clipAttr>
<rect x="0" y="0" width="108" height="108" fill="url(#bg)"/>
<g transform="translate(54 54) scale($(Num $inner)) translate(-54 -54)">
$(Convert-Vector 'bl_icon_arrows')
$(Convert-Vector 'bl_icon_center')
</g>
</g>
</svg>
"@

# ---------- render with headless Edge

$tmp = Join-Path ([IO.Path]::GetTempPath()) ("bl-icon-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $tmp | Out-Null
try {
    $html = Join-Path $tmp 'icon.html'
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($html, "<!doctype html><html><head><style>html,body{margin:0;padding:0;background:transparent;overflow:hidden}svg{display:block}</style></head><body>$svg</body></html>", $utf8)
    [IO.File]::WriteAllText((Join-Path $tmp 'icon.svg'), $svg, $utf8)
    $png = Join-Path $tmp 'shot.png'
    $argList = @('--headless=new', '--disable-gpu', '--hide-scrollbars', '--default-background-color=00000000',
        "--user-data-dir=$tmp\profile", "--window-size=$Size,$Size", "--screenshot=$png", ([Uri]$html).AbsoluteUri)
    $p = Start-Process -FilePath $Browser -ArgumentList $argList -Wait -PassThru -WindowStyle Hidden
    if (-not (Test-Path $png)) { throw "Rendering failed (browser exit code $($p.ExitCode))" }
    $outDir = Split-Path ([IO.Path]::GetFullPath($Out))
    if ($outDir) { New-Item -ItemType Directory -Force $outDir | Out-Null }
    Copy-Item $png $Out -Force
    Copy-Item (Join-Path $tmp 'icon.svg') ([IO.Path]::ChangeExtension($Out, '.svg')) -Force
    Write-Host "Wrote $Out (and .svg)"
}
finally {
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
