$ErrorActionPreference = "Stop"
$files = Get-ChildItem src -Recurse -File | Where-Object {
    $_.Extension -in @(".java", ".c", ".cpp", ".h", ".hpp")
}
$violations = @($files | Select-String -Pattern '[/][*]|[*][/]|[/][/]')
if ($violations.Count -gt 0) {
    $violations | ForEach-Object {
        Write-Error "$($_.Path):$($_.LineNumber): $($_.Line.Trim())"
    }
    throw "Source comments and Javadoc are not allowed"
}
Write-Host "Source comment policy passed for $($files.Count) files."