# 빌드 스크립트 (Windows PowerShell 5.1+ / JDK 17+)
# - Maven 없이 javac 로 빌드한다.
# - SQLite JDBC 와 slf4j 를 Maven Central 에서 내려받는다.
# - 무결성: 소스에 고정한 SHA-256 과 매 빌드마다 대조한다(다운로드 서버의 해시 파일에 의존하지 않음).
#   버전을 올릴 때는 Maven Central 에서 받은 파일의 SHA-1 을 공식 .sha1 과 대조한 뒤 새 SHA-256 을 여기에 기록한다.
# 사용: powershell -ExecutionPolicy Bypass -File .\build.ps1 [-SkipTests]
param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$deps = @(
  @{ g='org/xerial'; a='sqlite-jdbc'; v='3.47.1.0'; sha256='4164d95347aeab42b754cabb7945b93e54f29ddb2eded967dbe979b4045cb685' },
  @{ g='org/slf4j';  a='slf4j-api';   v='1.7.36';   sha256='d3ef575e3e4979678dc01bf1dcce51021493b4d11fb7f1be8ad982877c16a1c0' },
  @{ g='org/slf4j';  a='slf4j-nop';   v='1.7.36';   sha256='c214958b07816cb4412b30c7bdbd4308ffdc6ba2a83767b8f3a9229cbd9274d6' },
  @{ g='org/mariadb/jdbc'; a='mariadb-java-client'; v='3.5.8'; sha256='6127dc7858047b3d4482899139640b0e2ab2b8abdeb708cfb8c011117771cddf' }
)
New-Item -ItemType Directory -Force lib | Out-Null
foreach ($d in $deps) {
  $jar = "lib\$($d.a)-$($d.v).jar"
  if (-not (Test-Path $jar)) {
    $base = "https://repo1.maven.org/maven2/$($d.g)/$($d.a)/$($d.v)/$($d.a)-$($d.v).jar"
    Write-Host "다운로드: $base"
    Invoke-WebRequest -UseBasicParsing -Uri $base -OutFile $jar
  }
  $actual = (Get-FileHash -Algorithm SHA256 $jar).Hash.ToLower()
  if ($actual -ne $d.sha256) { Remove-Item $jar; throw "SHA-256 불일치(변조 또는 손상 의심): $jar" }
}
Write-Host "의존성 SHA-256 확인 완료"

$javac = 'javac'
if ($env:JAVA_HOME) { $javac = Join-Path $env:JAVA_HOME 'bin\javac.exe' }
$cp = (Get-ChildItem lib\*.jar | ForEach-Object { 'lib\' + $_.Name }) -join ';'

if (Test-Path build) { Remove-Item -Recurse -Force build }
New-Item -ItemType Directory -Force build\classes, build\test-classes | Out-Null

# 한글 등 비ASCII 경로 문제를 피하려고 프로젝트 기준 상대 경로를 인자 파일에 기록한다
$src = Get-ChildItem -Recurse src\main\java -Filter *.java | ForEach-Object { Resolve-Path -Relative $_.FullName }
$src | Set-Content -Encoding ASCII build\sources.txt
& $javac -encoding UTF-8 --release 17 -d build\classes -cp $cp '@build\sources.txt'
if ($LASTEXITCODE -ne 0) { throw "컴파일 실패" }
Copy-Item -Recurse -Force src\main\resources\* build\classes\

$tsrc = Get-ChildItem -Recurse src\test\java -Filter *.java | ForEach-Object { Resolve-Path -Relative $_.FullName }
$tsrc | Set-Content -Encoding ASCII build\test-sources.txt
& $javac -encoding UTF-8 --release 17 -d build\test-classes -cp "build\classes;$cp" '@build\test-sources.txt'
if ($LASTEXITCODE -ne 0) { throw "테스트 컴파일 실패" }

# 실행용 jar (의존성은 lib\ 에서 classpath 로 로드)
$manifest = "Main-Class: screener.App`r`nClass-Path: " + ((Get-ChildItem lib\*.jar | ForEach-Object { "lib/" + $_.Name }) -join ' ') + "`r`n"
Set-Content -Encoding ASCII build\MANIFEST.MF $manifest
$jarTool = 'jar'
if ($env:JAVA_HOME) { $jarTool = Join-Path $env:JAVA_HOME 'bin\jar.exe' }
& $jarTool --create --file screener.jar --manifest build\MANIFEST.MF -C build\classes .
if ($LASTEXITCODE -ne 0) { throw "jar 생성 실패" }
Write-Host "빌드 완료: screener.jar"

if (-not $SkipTests) {
  $java = 'java'
  if ($env:JAVA_HOME) { $java = Join-Path $env:JAVA_HOME 'bin\java.exe' }
  & $java '-Dfile.encoding=UTF-8' -cp "build\test-classes;build\classes;$cp" screener.TestMain
  if ($LASTEXITCODE -ne 0) { throw "테스트 실패" }
}
