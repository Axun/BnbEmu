# BnbEmu Launcher

⚠ 纯Vibe Coding开发，代码未经人工审查。

Windows 桌面启动器（WPF / .NET 9 / C#）：用 git 拉取 BnbEmu 最新源码、准备 JDK 与 Maven，
一键运行后台服务；同时能把 `resource` 子模块里的客户端资源拼成一个可运行的客户端目录并直接启动游戏。
所有文件都放在启动器所在目录，不写注册表、不改系统环境变量。

## 功能

- **源码走 git，不是 zip 下载**：内置 libgit2（LibGit2Sharp），目标机器**不需要装 git**。
  首次运行 `git clone` 当前分支到 `runtime/source`；之后启动只 `git fetch` 比对，显示「有 N 个新提交」，
  由用户点「拉取更新」执行 `pull`（快进对齐远端），不会擅自覆盖
- **客户端资源按需拉取**：`.gitmodules` 里的 `resource`（客户端资源，几百 MB）不跟源码一起下，
  点「启动客户端」时才初始化/更新这个子模块
- **一键启动客户端**：把 `resource/other` 里的 exe/dll/字体复制到 `runtime/client`，
  再把 `resource/{fx,CharTE,EmoTE}` 三个解包目录重新打包成 `fx.idd/idx` 等（C# 实现，
  不依赖 python，见下文），最后用该目录作为工作目录启动 `CA.exe`
- **提交详情**：列出本地版本到远端之间的所有提交（sha / 说明 / 作者 / 时间），可复制
- **JDK 21 自动安装**：可选用 Amazon Corretto 21 或 Azul Zulu 21，缺失时自动下载解压到 `runtime/jdk`
- **Maven 自动安装**：从 Apache 官方镜像取最新的 3.9.x，解压到 `runtime/maven`；本地仓库按目录探测：
  用户目录下存在 `%USERPROFILE%\.m2` 就复用它（`%USERPROFILE%\.m2\repository`，省掉整份依赖重下），
  不存在才用 `runtime/m2repo`。两种情况都显式传 `-Dmaven.repo.local`，不靠 mvn 的默认值
- **后台服务**：编译与运行分离。只有源码提交 / pom.xml / JDK 变化（或在源码里手改过）时才在
  `runtime/source` 执行一次 `mvn compile`，产物记进 `runtime/build/build-stamp.json`；
  平时启动直接用 `java -cp "target\classes;依赖 jar" bnbemu.MainKt`，不再每次都让 Kotlin 全量重编
  （老版本 `mvn compile exec:java` 每次要十几秒，而且服务跑在 Maven 的 JVM 里，堆上限被 MAVEN_OPTS 限制）。
  编译前会清空 `target\classes`，避免源文件删除后残留 class 被 ClassGraph 扫成幽灵 handler。
  服务进程仍用 Job Object 托管，启动器退出时不会留下孤儿 java 进程
- **启动器自己会更新**：从 GitHub Release 读 `latest.json`，发现新版本在顶部提示；
  下载后校验 SHA-256，退出后由脚本把 exe 换掉（旧版本留作 `.prev` 可回退），`runtime` 目录完全不动
- **不污染系统**：JDK / Maven / 源码 / 客户端 / 日志全部在启动器目录内；Maven 仓库只**复用**已有的
  `%USERPROFILE%\.m2`，用户没有才建 `runtime/m2repo`——不会凭空往用户目录写东西

## 目录结构

```
<启动器目录>\
  BnbLauncher.exe
  BnbLauncher.exe.prev      # 上一版本，替换时留下；新版成功启动两次后自动清理
  update-apply.cmd          # 替换脚本（纯 ASCII + 相对路径，换完 exe 自己留着）
  launcher.config.json      # 设置（仓库、分支、JDK 供应商、代理、更新源…）
  runtime\
    state.json              # 已安装的提交与组件版本
    update\                 # 自更新暂存区：new.exe（下好的新版）、pending.json
    jdk\                    # JDK 21
    maven\                  # Maven 3.9.x
    m2repo\                 # Maven 本地仓库（仅当 %USERPROFILE%\.m2 不存在时才用）
    build\                  # 服务端构建产物：server-cp.txt（依赖 classpath）、build-stamp.json（编译凭证，含仓库路径）
    source\                 # BnbEmu 源码（标准 git 仓库，含 .git）
      target\classes\       # 编译输出；每次重编前整个清空
      resource\             # 客户端资源（git 子模块，按需初始化）
    client\                 # 客户端运行目录（启动器拼出来的）
      CA.exe + *.dll + *.fft + nm.bik
      fx.idd / fx.idx / CharTE.* / EmoTE.*
      idd-stamps.json       # 各资源目录的打包指纹
    cache\                  # 下载缓存（装成功后安装包即删，只在失败重试时留着）
  logs\
    launcher-YYYYMMDD.log
```

启动器只认自己目录下的 `runtime`，删掉整个 `runtime` 就回到全新状态；
只想重打资源包的话，删掉 `runtime\client` 即可。

## 客户端资源打包

`runtime\client` 里的三个 `*.idd` / `*.idx` 是启动器**现打**的：算法照搬
`tools/data/idd_extract.py` + `idd_build.py`（依据 `reference/CA.exe.c` 反编译），
用 C# 重写在 `Services/IddCodec.cs` / `Services/IddArchiveBuilder.cs` 里。

- 输入是子模块里 `fx`、`CharTE`、`EmoTE` 三个解包目录及其 `_idd_manifest.json`
- 条目按大小写不敏感的字典序重排（客户端 `sub_767490` 用 `_mbsicmp` 二分查找，顺序错就查不到资源）
- `seeded` 块重新加密并重算 `auxiliary = CRC32(seed, 明文)`，`_mm` 块重新压成 zlib 容器
- 没改过的条目直接复用上一次产物（或 manifest 里记录的原始 `.idd`）里的块字节，
  所以「原样解包 → 原样打包」逐字节一致，改一个文件也只重编那一块
- 打完会回读校验：idx 头/顺序/条目参数逐条比对，seeded 重算 CRC、`_mm` 解压比长度
- 资源目录用「相对路径 + 大小 + 修改时间」的滚动哈希当指纹，存在 `client\idd-stamps.json`；
  指纹没变就跳过整个打包（点一次启动约 1 秒），想强制重打点「重新打包」

## 启动器自更新

只换 `BnbLauncher.exe` 一个文件：`runtime\`（JDK、Maven、源码、客户端资源、m2 仓库）完全不动，
所以一次更新就是下载一个 exe，不用重装、也不用重下几百 MB 的资源。

没用 Velopack / Squirrel 这类框架：它们的模型是「整个安装目录归框架管、每次更新重建 `current\`」，
会逼着把 `runtime` 搬出去、老用户重下。这里只需要「换一个文件」这一件事。

### 流程

1. 启动时 `GET` 清单（依次尝试 `releases/download/launcher-latest/latest.json`
   和 `raw.../launcher/latest.json`），比版本号；有新版就在窗口顶部出横幅
2. 点「下载更新」→ 下到 `runtime\update\new-<版本>.exe.part`（支持断点续传）→ 校验长度与 SHA-256
3. 点「立即重启并更新」，或直接关掉启动器 → 写 `runtime\update\pending.json`、
   起一个脱离父进程的 `update-apply.cmd`，然后启动器退出
4. 脚本等启动器 PID **彻底退出**后：`BnbLauncher.exe` → `.prev`，`new.exe` → `BnbLauncher.exe`，
   再把启动器拉起来。任一步失败都会把 `.prev` 改回去，不会留下「目录里没有 exe」的状态
5. 下次启动对账：版本已经是目标版本就记一次成功，**连续成功两次**才删 `.prev`

几个实现细节值得记一笔：

- 运行中的 exe 不能删、不能覆盖，但**可以在同卷内改名** —— 整个替换流程就建立在这条上
- 替换脚本里没有任何路径字面量（全靠 `%~dp0` 和相对名），所以安装目录带中文也不会乱码；
  外部命令全部写成 `%SystemRoot%\System32\xxx.exe`，否则装了 Git for Windows 的机器上
  GNU `find` 会抢跑，等待循环直接失效、在 exe 还被占用时就去 move
- 替换脚本必须等进程退干净：启动器用 Job Object 托管服务，自己退出会带走 java，
  所以默认策略是「下好、下次启动生效」，点「立即重启并更新」会先把服务停掉

### 强制更新

清单里的 `minVersion` 高于当前版本时，启动服务 / 启动客户端会一起禁用，横幅上的按钮也只剩更新可用。
发布侧由 workflow 的 `MIN_VERSION` 控制，平时是 `0.0.0`（不强制）。

### 发布

```powershell
git tag launcher-v1.1.0
git push origin launcher-v1.1.0
```

`.github/workflows/launcher-release.yml` 会：publish → 算 SHA-256 → 生成 `latest.json` →
建版本 release（资产 `BnbLauncher.exe` + `latest.json`）→ 再把 `latest.json` 覆盖上传到
滚动 release `launcher-latest`。客户端默认读后者，路径固定。

仓库里的 `launcher/latest.json` 是**兜底副本**（第一个源 404 时才会走到 raw），
workflow 不会自动提交它——想让兜底也生效，发版后手工同步一份。内容和 release 里那份一致即可。

两个约定：

- **不要**用 `/releases/latest/download/latest.json` 当清单地址：本仓库还有客户端资源的
  release，那个「latest」随时会被客户端那条抢走
- 版本号只有一个来源就是 tag（作为 `-p:Version` 传给 publish，界面右下角显示的就是它）

发完版可以用命令行验一遍清单和资产是否配套：

```powershell
.\BnbLauncher.exe --check-update --download
```

### 回退

新版起不来时，把 `BnbLauncher.exe.prev` 改名回 `BnbLauncher.exe` 即可；
或者删掉 `runtime\update\pending.json`，让启动器重新检查一遍。

### 已知限制

- 只有文件名是 `BnbLauncher.exe` 时才会自更新（脚本按固定文件名操作），改名的副本会禁用更新
- 没有代码签名：SmartScreen 仍会提示「未知发布者」；SHA-256 只能防下载损坏和镜像篡改，
  防不了 release 本身被换

## 构建

需要 .NET 9 SDK：

```powershell
# 调试运行
dotnet run --project BnbLauncher

# 发布单文件（自包含，目标机器不需要装 .NET）
dotnet publish BnbLauncher -c Release -o dist
```

或直接双击 `build.cmd`，产物在 `dist\BnbLauncher.exe`（单文件，约 60 MB）。

### 关于 AOT

`.NET 9` 的 WPF **不支持** NativeAOT / 裁剪，开 `PublishAot=true` 会直接报
`error NETSDK1168: 启用剪裁时，不支持或不推荐使用 WPF`（WPF、WinForms 两个桌面栈都不支持）。
因此这里用「自包含 + 单文件 + 压缩」发布，效果上仍是「一个 exe，拷走即用」。
`BnbLauncher.csproj` 里保留了 `PublishAot` 开关与注释，将来 WPF 支持 AOT 时改一行即可。

## 命令行（无界面）

```powershell
.\BnbLauncher.exe --selftest            # 检查远端分支 + 准备 JDK/Maven + clone（本地无仓库时）
.\BnbLauncher.exe --selftest --build    # 再按需编译一次服务端（不启动服务，端口被占时也能验编译）
.\BnbLauncher.exe --selftest --server   # 再把服务真正跑一遍（约 3 分钟）后停止

.\BnbLauncher.exe --check-update        # 只查启动器自身有没有新版本（不发版也能验清单）
.\BnbLauncher.exe --check-update --download   # 顺便下载并校验 SHA-256，验证资产与清单配套

.\BnbLauncher.exe --prepare-client      # 只准备客户端目录（拉子模块/复制/打包），不启动游戏
.\BnbLauncher.exe --prepare-client --force   # 忽略指纹，强制重打三个资源包

.\BnbLauncher.exe --pack-idd <解包目录> <输出基名> [--force]
                                        # 单独把某个解包目录打成 idd/idx，等价于 idd_build.py
```

`--selftest` 的结果输出到控制台，同时写入 `logs\selftest-*.log`。
`--check-update` 的退出码：0 = 检查跑通（不管有没有新版本），1 = 失败。

## 配置项

| 字段 | 说明 |
| --- | --- |
| `owner` / `repo` / `branch` | 源码仓库与分支，默认 `krrr/BnbEmu@main` |
| `jdkVendor` | `Corretto`（默认）或 `Zulu` |
| `autoStartServer` | 环境就绪后自动运行服务，默认 `true` |
| `autoCheckUpdates` | 启动时检测远端更新（只提示），默认 `true` |
| `autoCheckLauncher` | 启动时检查启动器自身更新，默认 `true` |
| `proxy` | 可选。HTTPS 代理，如 `http://127.0.0.1:7890`；留空自动检测环境变量 / 系统代理 / git 全局 `http.proxy`。libgit2 只支持 http/https 代理，SOCKS 会被忽略并退化为直连 |

## 说明

- 只支持 Windows 10 及以上（x64）。
- `runtime/source` 是标准 git 仓库，可直接进去 `git log` / `git diff` 排查；
  启动器只做 clone / fetch / pull，`state.json` 里的版本始终以仓库 HEAD 为准。
- 更新是对齐远端 tip（等价 `git pull --ff-only`，分叉时强制 `reset --hard`）。
  本地手改过源码时界面会先弹确认，避免误删。
- 若服务正在运行，源码目录会被占用，同步前请先停止服务（界面会禁用按钮）。
- 早期版本用 zip 落地的 `runtime/source` 没有 `.git`：启动时旧目录先挪到 `source.old-xxxxxx`，
  重新 clone 成功后删除，失败会自动还原。
- 客户端进程是**独立**进程：启动器退出不会带走它（服务则会被带走）。
  客户端连的是 `127.0.0.1`——`resource/other` 里的 exe/dll 是已经打过地址补丁的版本，
  想改成别的地址用 `tools/patch_ip.py` 重打后重新「准备客户端」。
- 端口：游戏 3838，Nexon passport 47611。
