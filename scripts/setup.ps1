#Requires -Version 5.0
<#
.SYNOPSIS
    Maven For Search 插件开发环境一键配置 (Windows)
.DESCRIPTION
    检测并搭建缺失环境: JDK 21, Gradle 依赖缓存, 国内镜像加速。
    所有产物离开 C 盘, 由用户指定安装根目录。
    幂等: 重复运行不重复下载。
.NOTES
    使用: 在项目根目录右键 "用 PowerShell 运行" 或执行 pwsh -File scripts\setup.ps1
#>

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Script:JdkPath = ''

function Write-Step { param([string]$msg) Write-Host "`n[STEP] $msg" -ForegroundColor Cyan }
function Write-Ok   { param([string]$msg) Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Warn2{ param([string]$msg) Write-Host "  [WARN] $msg" -ForegroundColor Yellow }
function Write-Err2 { param([string]$msg) Write-Host "  [ERR] $msg" -ForegroundColor Red }

# ---------- 1. 确认安装根目录 (使用脚本执行的当前目录) ----------
function Get-InstallRoot {
    Write-Step '确认安装根目录'
    $root = (Get-Location).Path
    Write-Host "  将使用当前目录作为安装根目录: $root"
    Write-Host "  会在此目录下创建: jdk21/ (JDK), gradle-home/ (Gradle 依赖缓存)"

    # 校验非 C 盘 (用户原始要求: 所有产物不能存 C 盘)
    $drive = ($root -split ':')[0].Trim()
    if ($drive -ieq 'C') {
        Write-Err2 "当前目录在 C 盘, 不允许存放环境产物。请切换到其他盘后重跑: cd D:\some\dir; powershell -File setup.ps1"
        exit 1
    }

    $confirm = Read-Host "确认在此目录安装? (回车继续, Ctrl+C 取消)"
    return $root
}

# ---------- 2. 检测 / 安装 JDK 21 ----------
function Test-Jdk21 {
    param([string]$HintDir)
    # 优先查 hint 目录
    if ($HintDir -and (Test-Path -LiteralPath $HintDir)) {
        $exe = Join-Path $HintDir 'bin\java.exe'
        if (Test-Path -LiteralPath $exe) {
            # 用 --version 输出到 stdout, 避免 -version 走 stderr 触发 ErrorActionPreference=Stop
            $ver = (& $exe --version 2>$null | Select-Object -First 1)
            if ($ver -match '\b21\.') {
                Write-Ok "检测到 JDK 21: $HintDir ($ver)"
                $Script:JdkPath = $HintDir
                return $true
            }
        }
    }
    # 查 JAVA_HOME
    $jh = $env:JAVA_HOME
    if ($jh -and (Test-Path -LiteralPath (Join-Path $jh 'bin\java.exe'))) {
        $ver = (& (Join-Path $jh 'bin\java.exe') --version 2>$null | Select-Object -First 1)
        if ($ver -match '\b21\.') {
            Write-Ok "检测到 JAVA_HOME 指向 JDK 21: $jh"
            $Script:JdkPath = $jh
            return $true
        }
    }
    return $false
}

function Install-Jdk21 {
    param([string]$Root)
    $jdkDir = Join-Path $Root 'jdk21'
    if (Test-Jdk21 -HintDir $jdkDir) { return }

    Write-Warn2 '未检测到 JDK 21, 开始下载 (华为云镜像)...'
    $url = 'https://mirrors.huaweicloud.com/openjdk/21.0.2/openjdk-21.0.2_windows-x64_bin.zip'
    $zip = Join-Path $Root 'openjdk21.zip'

    Write-Host "  下载: $url"
    try {
        Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
    } catch {
        Write-Err2 "下载失败: $($_.Exception.Message)"
        Write-Err2 '请手动下载 Temurin 21 并解压, 然后用环境变量 JAVA_HOME 指向它, 重跑本脚本'
        exit 1
    }

    Write-Host '  解压...'
    Expand-Archive -Path $zip -DestinationPath $Root -Force
    # 解压后目录名形如 jdk-21.0.2+13, 重命名为 jdk21
    $extracted = Get-ChildItem -Path $Root -Directory | Where-Object { $_.Name -like 'jdk-21*' } | Select-Object -First 1
    if (-not $extracted) {
        Write-Err2 '解压后未找到 jdk-21* 目录'
        exit 1
    }
    if (Test-Path -LiteralPath $jdkDir) { Remove-Item -LiteralPath $jdkDir -Recurse -Force }
    Rename-Item -LiteralPath $extracted.FullName -NewName 'jdk21' -Force
    Remove-Item -LiteralPath $zip -Force

    if (-not (Test-Jdk21 -HintDir $jdkDir)) {
        Write-Err2 '安装后仍检测失败'
        exit 1
    }
}

# ---------- 3. 配置环境变量 ----------
function Set-EnvVars {
    param([string]$JdkHome, [string]$GradleHome)
    Write-Step '配置环境变量 (用户级, 永久)'
    # setx 截断 1024 字符, PATH 用 user PATH 避免
    setx JAVA_HOME $JdkHome | Out-Null
    setx GRADLE_USER_HOME $GradleHome | Out-Null

    # PATH 追加 (去重)
    $userPath = [Environment]::GetEnvironmentVariable('PATH', 'User')
    $entries = if ($userPath) { $userPath -split ';' } else { @() }
    $toAdd = @("$JdkHome\bin")
    $changed = $false
    foreach ($e in $toAdd) {
        if ($entries -notcontains $e) {
            $entries += $e
            $changed = $true
        }
    }
    if ($changed) {
        $newPath = ($entries | Where-Object { $_ }) -join ';'
        setx PATH $newPath | Out-Null
        Write-Ok "PATH 已追加: $JdkHome\bin"
    } else {
        Write-Ok 'PATH 已包含 JDK, 跳过'
    }
    # 当前会话也生效
    $env:JAVA_HOME = $JdkHome
    $env:GRADLE_USER_HOME = $GradleHome
    $env:PATH = "$JdkHome\bin;$env:PATH"
    Write-Ok "JAVA_HOME = $JdkHome"
    Write-Ok "GRADLE_USER_HOME = $GradleHome"
}

# ---------- 4. 改写项目配置文件 ----------
function Update-ProjectConfig {
    param([string]$JdkHome)
    Write-Step '改写项目配置文件'

    # 4.1 gradle-wrapper.properties: distributionUrl 改腾讯云镜像
    $wrapperFile = Join-Path $ProjectRoot 'gradle\wrapper\gradle-wrapper.properties'
    if (Test-Path -LiteralPath $wrapperFile) {
        $content = Get-Content -LiteralPath $wrapperFile -Raw
        if ($content -match 'services\.gradle\.org/distributions') {
            $newContent = $content -replace 'https://services\.gradle\.org/distributions/', 'https://mirrors.cloud.tencent.com/gradle/'
            Set-Content -LiteralPath $wrapperFile -Value $newContent -NoNewline
            Write-Ok "gradle-wrapper.properties: 已切换到腾讯云镜像"
        } else {
            Write-Ok 'gradle-wrapper.properties: 已是镜像, 跳过'
        }
    } else {
        Write-Warn2 "未找到 $wrapperFile"
    }

    # 4.2 gradle.properties: org.gradle.java.installations.paths 改为实际 JDK 路径
    $gpFile = Join-Path $ProjectRoot 'gradle.properties'
    if (Test-Path -LiteralPath $gpFile) {
        # 用正斜杠, 避免反斜杠转义问题
        $jdkPathNormalized = $JdkHome -replace '\\', '/'
        $content = Get-Content -LiteralPath $gpFile -Raw
        $pattern = 'org\.gradle\.java\.installations\.paths=.*'
        if ($content -match $pattern) {
            $newContent = $content -replace $pattern, "org.gradle.java.installations.paths=$jdkPathNormalized"
            Set-Content -LiteralPath $gpFile -Value $newContent -NoNewline
            Write-Ok "gradle.properties: installations.paths = $jdkPathNormalized"
        } else {
            Write-Warn2 'gradle.properties 未找到 installations.paths 配置项'
        }
    } else {
        Write-Warn2 "未找到 $gpFile"
    }
}

# ---------- 主流程 ----------
function Main {
    Write-Host '========================================' -ForegroundColor Cyan
    Write-Host '  Maven For Search 环境配置 (Windows)' -ForegroundColor Cyan
    Write-Host '========================================' -ForegroundColor Cyan

    $root = Get-InstallRoot
    Install-Jdk21 -Root $root
    $gradleHome = Join-Path $root 'gradle-home'
    if (-not (Test-Path -LiteralPath $gradleHome)) {
        New-Item -ItemType Directory -Path $gradleHome -Force | Out-Null
    }
    Set-EnvVars -JdkHome $Script:JdkPath -GradleHome $gradleHome
    Update-ProjectConfig -JdkHome $Script:JdkPath

    Write-Step '完成'
    Write-Host '  环境配置完成。请重新打开 PowerShell 窗口使环境变量生效。' -ForegroundColor Green
    Write-Host '  然后执行:' -ForegroundColor Green
    Write-Host "    cd $ProjectRoot" -ForegroundColor Green
    Write-Host '    .\gradlew.bat buildPlugin -x test' -ForegroundColor Green
    Write-Host ''
    Write-Host '  注意: 首次构建会下载 IntelliJ Platform SDK (~800MB), 无国内镜像, 耐心等待。' -ForegroundColor Yellow
}

# 仅在直接执行时运行主流程 (dot-source 时只加载函数, 不执行)
if ($MyInvocation.InvocationName -ne '.') {
    Main
}
