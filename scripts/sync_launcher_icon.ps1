param()
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$sourceFile = Join-Path $projectRoot 'app\src\main\ic_launcher-playstore.png'
$resourceRoot = Join-Path $projectRoot 'app\src\main\res'
$drawable = Join-Path $resourceRoot 'drawable-nodpi'
New-Item -ItemType Directory -Force -Path $drawable | Out-Null
# Keep the user's original artwork intact; copy it verbatim for Adaptive Icon.
Copy-Item -LiteralPath $sourceFile -Destination (Join-Path $drawable 'toolbox_icon_source.png') -Force
$sourceImage = [Drawing.Image]::FromFile($sourceFile)
try {
    foreach ($density in @(@('mdpi',48), @('hdpi',72), @('xhdpi',96), @('xxhdpi',144), @('xxxhdpi',192))) {
        $size = [int]$density[1]
        $directory = Join-Path $resourceRoot ('mipmap-' + $density[0])
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
        $bitmap = New-Object Drawing.Bitmap($size, $size)
        $graphics = [Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.Clear([Drawing.Color]::White)
            $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $scale = [Math]::Min($size / $sourceImage.Width, $size / $sourceImage.Height)
            $width = [single]($sourceImage.Width * $scale)
            $height = [single]($sourceImage.Height * $scale)
            $graphics.DrawImage($sourceImage, [single](($size - $width) / 2), [single](($size - $height) / 2), $width, $height)
            foreach ($name in @('toolbox_launcher', 'toolbox_launcher_round')) {
                $bitmap.Save((Join-Path $directory "$name.png"), [Drawing.Imaging.ImageFormat]::Png)
            }
        } finally { $graphics.Dispose(); $bitmap.Dispose() }
    }
} finally { $sourceImage.Dispose() }
Write-Output 'Synced supplied artwork to Adaptive Icon and legacy launcher resources. Original file unchanged.'
