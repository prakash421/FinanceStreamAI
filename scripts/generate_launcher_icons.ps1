param(
  [string]$Source = "$PSScriptRoot\..\StockWizAI.png",
  [string]$ResBase = "$PSScriptRoot\..\app\src\main\res"
)

Add-Type -AssemblyName System.Drawing

$orig = [System.Drawing.Image]::FromFile((Resolve-Path $Source))
$side = [Math]::Min($orig.Width, $orig.Height)
$x = [int](($orig.Width  - $side) / 2)
$y = [int](($orig.Height - $side) / 2)

$square = New-Object System.Drawing.Bitmap $side, $side
$g = [System.Drawing.Graphics]::FromImage($square)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.SmoothingMode     = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
$g.PixelOffsetMode   = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$srcRect = New-Object System.Drawing.Rectangle $x, $y, $side, $side
$dstRect = New-Object System.Drawing.Rectangle 0, 0, $side, $side
$g.DrawImage($orig, $dstRect, $srcRect, [System.Drawing.GraphicsUnit]::Pixel)
$g.Dispose()
$orig.Dispose()

$sizes = [ordered]@{
  "mipmap-mdpi"    = 48
  "mipmap-hdpi"    = 72
  "mipmap-xhdpi"   = 96
  "mipmap-xxhdpi"  = 144
  "mipmap-xxxhdpi" = 192
}

foreach ($folder in $sizes.Keys) {
  $dim    = $sizes[$folder]
  $outDir = Join-Path $ResBase $folder
  if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

  # Square icon
  $bmp = New-Object System.Drawing.Bitmap $dim, $dim
  $gg  = [System.Drawing.Graphics]::FromImage($bmp)
  $gg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $gg.SmoothingMode     = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
  $gg.PixelOffsetMode   = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
  $gg.DrawImage($square, 0, 0, $dim, $dim)
  $gg.Dispose()
  $bmp.Save((Join-Path $outDir "ic_launcher.png"), [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()

  # Round icon (circular mask, transparent background)
  $round = New-Object System.Drawing.Bitmap $dim, $dim
  $gr    = [System.Drawing.Graphics]::FromImage($round)
  $gr.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $gr.SmoothingMode     = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
  $gr.PixelOffsetMode   = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
  $path = New-Object System.Drawing.Drawing2D.GraphicsPath
  $path.AddEllipse(0, 0, $dim, $dim)
  $gr.SetClip($path)
  $gr.DrawImage($square, 0, 0, $dim, $dim)
  $gr.Dispose()
  $round.Save((Join-Path $outDir "ic_launcher_round.png"), [System.Drawing.Imaging.ImageFormat]::Png)
  $round.Dispose()

  Write-Host "Wrote $folder ($dim x $dim)"
}

$square.Dispose()
Write-Host "Done."
