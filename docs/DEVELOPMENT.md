# Maven For Search 开发流程规范

本文档定义项目的 Git 分支模型、开发迭代流程与发布操作，所有开发（含 AI 辅助开发）必须遵循。

## 1. 分支模型

| 分支      | 用途                              | 保护规则                     |
| ------- | ------------------------------- | ------------------------ |
| `main`  | 稳定发布分支，每次 Release 对应 main 上的一个 tag | 只接受来自 `dev` 的合并，禁止直接提交新功能  |
| `dev`   | 日常开发分支，所有新功能 / bugfix 在此开发        | 可自由提交；合并 main 前必须通过测试      |
| 临时分支    | 大功能可用 `feature/xxx` 从 dev 切出      | 完成后合并回 dev 并删除           |

```
main ────●────────────────────●──── (tag v1.0.6)  (tag v1.0.7)
          \                  /
dev    ────\──●──●──●──●────/──────── (日常开发、测试)
              feature/xxx ─┘
```

## 2. 标准迭代流程

每次开发按以下 6 步执行：

### 第 1 步：在 dev 分支开发

```bash
# 确保 dev 分支存在并切到最新（首次: git checkout -b dev; git push -u origin dev）
git checkout dev
git pull origin dev

# ... 编码、本地验证 ...

# 提交（多次小步提交优于一次大提交）
git add <文件>
git commit -m "feat: 简要描述变更"
git push origin dev
```

提交信息规范：`feat:` 新功能 / `fix:` 修复 / `docs:` 文档 / `refactor:` 重构 / `test:` 测试 / `chore:` 构建杂项。

### 第 2 步：本地测试验证

```bash
# 单元测试（必须全部通过）
./gradlew.bat test

# 完整打包（含插件校验，产物在 build/distributions/）
./gradlew.bat buildPlugin
```

验证清单：
- [ ] `test` 全部通过
- [ ] `buildPlugin` BUILD SUCCESSFUL
- [ ] 将 `build/distributions/*.zip` 安装到 IDEA 实测核心功能（搜索 / 中文输入法 / 版本弹窗 / 复制 XML）

### 第 3 步：合并到 main

```bash
git checkout main
git pull origin main
git merge --no-ff dev -m "chore: merge dev -> main (v1.0.x)"
```

> 使用 `--no-ff` 保留合并记录，便于回溯每次发布包含的迭代。

### 第 4 步：同步版本号与文档（合并后、打 tag 前）

一次发布需同步修改以下位置（以 1.0.7 为例）：

| 文件                            | 修改内容                                  |
| ----------------------------- | ------------------------------------- |
| `build.gradle.kts`            | `version = "1.0.7"`                   |
| `gradle.properties`           | `pluginVersion=1.0.7`                 |
| `src/main/resources/META-INF/plugin.xml` | `<version>1.0.7</version>` 与 `<change-notes>` 顶部追加本版说明 |
| `README.md`                   | 构建产物文件名、安装说明文件名、版本历史表追加一行               |
| `docs/REQUIREMENTS.md`        | 版本历史表追加一行                             |

```bash
git add -u
git commit -m "chore: bump version to 1.0.7"
```

### 第 5 步：推送 main 并打 tag

```bash
git push origin main

# 附注标签（含发布说明），命名规范: v<主>.<次>.<修订>
git tag -a v1.0.7 -m "v1.0.7: 修复中文输入法搜索 bug"
git push origin v1.0.7
```

### 第 6 步：创建 GitHub Release 并上传产物

见下文第 3 节。Release 创建成功后，本轮迭代结束；继续开发回到第 1 步（dev 分支）。

## 3. 推送 Release（两种方式）

打包产物固定路径：`build/distributions/maven-for-search-<version>.zip`。

### 方式 A：GitHub CLI（推荐，需先安装 [gh](https://cli.github.com/)）

```bash
gh auth login                      # 首次登录
gh release create v1.0.7 build/distributions/maven-for-search-1.0.7.zip \
  --title "v1.0.7 - 修复中文输入法搜索 bug" \
  --notes "## 修复\n- ...变更说明..."
```

### 方式 B：无 gh CLI（PowerShell + GitHub API）

利用 Git Credential Manager 已存储的 GitHub 凭据（`git push` 能成功即可用），在**仓库根目录**执行：

```powershell
# 1) 取出 GCM 存储的 token
$cred = @("protocol=https", "host=github.com", "") | git credential fill
$token = ($cred | Where-Object { $_ -like 'password=*' }).Substring(9)
$headers = @{ Authorization = "Bearer $token"; 'User-Agent' = 'maven-search-release' }
$api = 'https://api.github.com/repos/<owner>/Maven-For-Search'

# 2) 创建 release（body 为 Markdown 发布说明）
$rel = Invoke-RestMethod -Uri "$api/releases" -Method Post -Headers $headers `
  -ContentType 'application/json; charset=utf-8' `
  -Body (@{ tag_name = "v1.0.7"; name = "v1.0.7"; body = "## 变更说明"; draft = $false; prerelease = $false } | ConvertTo-Json)

# 3) 上传 zip 产物
$upload = $rel.upload_url -replace '\{\?name,label\}', '?name=maven-for-search-1.0.7.zip'
Invoke-RestMethod -Uri $upload -Method Post -Headers $headers -ContentType 'application/zip' `
  -InFile 'build\distributions\maven-for-search-1.0.7.zip'
```

> 注意：token 需具备 `repo` 权限（GCM 默认 OAuth 即包含）。若凭据是只读 PAT，会返回 401/403。

## 4. 常用 Git 命令速查

```bash
# ---- 拉取 ----
git clone https://github.com/wawlp9394/Maven-For-Search.git   # 首次克隆
git pull origin dev                                            # 拉取远端更新（先确认当前分支）
git fetch --all --tags                                         # 只抓取不合并

# ---- 提交 ----
git status                       # 查看改动
git add <文件>                   # 暂存指定文件（避免 git add . 误加垃圾文件）
git diff                         # 查看未暂存改动
git commit -m "type: 描述"       # 提交
git log --oneline -10            # 查看历史

# ---- 分支 ----
git branch                       # 列出本地分支
git checkout -b dev              # 创建并切换
git switch main                  # 切换分支
git merge --no-ff dev            # 合并 dev 到当前分支
git branch -d dev                # 删除已合并分支
git push -u origin dev           # 首次推送并建立跟踪

# ---- 标签 ----
git tag                          # 列出本地 tag
git tag -a v1.0.7 -m "说明"      # 创建附注 tag
git push origin v1.0.7           # 推送单个 tag
git push --tags                  # 推送所有 tag（谨慎）

# ---- 撤销（提交前）----
git restore <文件>               # 丢弃工作区改动
git restore --staged <文件>      # 取消暂存

# ---- 撤销（提交后，未推送）----
git reset --soft HEAD~1          # 回退提交但保留改动
git reset --hard HEAD~1          # 彻底丢弃（危险）

# ---- 已推送到远端的提交不要 reset --hard + force push ----
```

## 5. 其他约定

- **构建产物不入库**：`build/`、`.gradle/`、`*.zip` 已在 `.gitignore` 中，Release 附件走 GitHub 上传而非 git 提交。
- **垃圾文件及时清理**：每次实现后删除临时脚本 / 测试残留，`git status` 干净才允许提交。
- **所有文件修改、下载不得超出项目范围**：依赖缓存统一落到 `GRADLE_USER_HOME`（非系统盘）。
- **AI 辅助开发同样遵循本流程**：开发在 dev 提交，验证通过后合并 main、打 tag、推送 Release。
