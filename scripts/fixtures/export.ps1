# =============================================
# 회귀 테스트 픽스처 추출
# 로컬 시딩 DB(boda-pg) -> src/test/resources/fixtures/seed-data.sql
#
# 사용법 (레포 루트에서):
#   powershell -ExecutionPolicy Bypass -File scripts\fixtures\export.ps1
# =============================================

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path "$PSScriptRoot\..\..").Path
$outDir = Join-Path $repoRoot "src\test\resources\fixtures"

function Invoke-Checked([scriptblock]$cmd, [string]$step) {
    & $cmd
    if ($LASTEXITCODE -ne 0) { throw "[$step] 실패 (exit $LASTEXITCODE)" }
}

# 1. 개인정보 제거
Invoke-Checked { docker cp "$PSScriptRoot\sanitize.sql" boda-pg:/tmp/sanitize.sql } "sanitize 복사"
Invoke-Checked { docker exec boda-pg psql -U boda -d boda -v ON_ERROR_STOP=1 -f /tmp/sanitize.sql } "sanitize 실행"

# 2. 데이터만 INSERT 문으로 덤프
Invoke-Checked {
    docker exec boda-pg pg_dump -U boda -d boda --data-only --column-inserts --disable-triggers `
        -t users -t policy_analysis -t coverage_item `
        -t terms_document -t terms_rider -t terms_clause -t terms_chunk `
        -f /tmp/seed-data.sql
} "pg_dump"

# 3. JDBC로 실행할 수 없는 줄 제거
#    - psql 메타 명령(\restrict 등)
#    - search_path를 비우는 설정 (테스트 커넥션에 남으면 이후 쿼리가 실패함)
Invoke-Checked {
    docker exec boda-pg sed -i -e '/^\\/d' -e "/set_config('search_path'/d" /tmp/seed-data.sql
} "정리"

# 4. 레포로 복사
New-Item -ItemType Directory -Force $outDir | Out-Null
Invoke-Checked { docker cp boda-pg:/tmp/seed-data.sql (Join-Path $outDir "seed-data.sql") } "복사"

Write-Host "픽스처 추출 완료: $outDir\seed-data.sql"
