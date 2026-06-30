# 环境配置脚本设计文档

**日期**: 2026-06-27
**目标**: 用户 clone 源码后, 一键检测并搭建缺失的开发环境, 所有产物离开 C 盘/系统盘, 配置国内镜像加速下载。

## 1. 范围

提供两份对等脚本:
- `scripts/setup.ps1` (Windows PowerShell 5+)
- `scripts/setup.sh` (Linux/macOS Bash)

## 2. 脚本职责

1. **询问安装根目录** → 用户输入, 校验非 C 盘 (Win) / 非系统盘, 创建子目录 `jdk21/`, `gradle-home/`
2. **检测 JDK 21** → 已有则记录路径; 缺失则从华为云镜像下载 Temurin 21 解压到 `jdk21/`
3. **设置 GRADLE_USER_HOME** → 指向 `<root>/gradle-home/` (Gradle 依赖缓存离开 C 盘)
4. **写入环境变量** → `JAVA_HOME`, `GRADLE_USER_HOME`, `PATH` 追加
   - Windows: `setx` 永久写入用户环境
   - Linux: 追加到 `~/.bashrc`
5. **改写项目配置文件**:
   - `gradle/wrapper/gradle-wrapper.properties`: `distributionUrl` 改阿里云镜像
   - `gradle.properties`: `org.gradle.java.installations.paths` 改为实际 JDK 路径
   - `build.gradle.kts`: 已有阿里云 Maven 镜像, 不动

## 3. 镜像源

| 资源 | 镜像 |
|------|------|
| JDK 21 (Temurin) | 华为云 `mirrors.huaweicloud.com/openjdk/` |
| Gradle 发行版 | 阿里云 `mirrors.aliyun.com/macports/distfiles/gradle/` |
| Maven 依赖 | 阿里云 `maven.aliyun.com` (项目已配) |
| IntelliJ Platform SDK | 无国内官方镜像, 脚本提示首次构建较慢 (~800MB) |

## 4. 关键决策

- **不改 `build.gradle.kts`**: Maven 镜像已配, 动它违反 surgical changes
- **仅替换 `gradle.properties` 的 `org.gradle.java.installations.paths` 一行**: 原值是开发者本机路径, 新用户必没有
- **幂等**: 重复运行不重复下载 (检测到已存在则跳过)
- **不自动安装 IntelliJ IDE**: 用户用本插件本就有 IDEA
- **不配置 Git**: 超范围

## 5. 验证策略

- 实跑 Windows 脚本到 "检测 JDK" 步骤 (已有 JDK 21 应跳过下载), 确认无报错
- 不实跑下载步骤 (避免真下 200MB JDK 占用磁盘)
- 验证后删除: 脚本生成的测试目录, 临时改写的配置文件备份

## 6. 成功标准

- Windows 上无 JDK 21 的新机器: 运行脚本 → 输入 D:\dev → 自动下载 JDK 21 到 D:\dev\jdk21, 配置环境变量, 改写项目配置, `./gradlew buildPlugin` 可成功
- 已有 JDK 21 的机器: 运行脚本 → 检测到 → 仅配置环境变量和项目配置文件, 不重复下载
- 重复运行: 跳过已下载文件, 仅重新校验配置
