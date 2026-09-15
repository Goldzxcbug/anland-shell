# 自定义启动环境变量功能

## 现状
启动应用的环境变量硬编码在 `DsCli.APP_ENV`（`ds/DsCli.java:47-51`）：`XDG_RUNTIME_DIR=/run/anland`、`WAYLAND_DISPLAY=wayland-0`、`XDG_SESSION_TYPE=wayland`、`MESA_LOADER_DRIVER_OVERRIDE=kgsl`、`GALLIUM_DRIVER=kgsl`、`FD_FORCE_KGSL=1`。在 `launchApp()`（365 行）以 `env K=V …` 前缀注入，在 `consolePreamble()`（440 行）以 `export` 注入。

## 设计决策
- **按容器设置**：与现有 `launch_user.<container>` 模式一致，顶栏新增按钮编辑当前激活容器的环境变量。
- **编辑形式**：多行等宽文本框，每行一个 `KEY=VALUE`（贴合终端工具风格，无 AndroidX 依赖下实现最简）。
- **合并语义**：内置默认值之上叠加用户自定义——同名覆盖；**值为空表示移除该内置变量**（如 `FD_FORCE_KGSL=` 即不注入它）；`#` 开头为注释行。这样既能增、改，也能删，无需完全重写默认值。
- **生效范围**：应用启动（`launchApp`）与控制台导出（`consolePreamble`）两处统一生效；探测到的 `DBUS_SESSION_BUS_ADDRESS/DISPLAY/XAUTHORITY` 仍追加在最后（优先生效）；`DS_NO_PROXY=1` 属 daemon 控制，保持硬编码。
- **快捷方式**：pinned shortcut 启动时从 Prefs 实时读取（不做 intent 快照），改完环境变量后所有入口立即生效。

## 改动清单

### 1. 新增 `ds/EnvVars.java`（约 90 行）
环境变量对的解析/校验/合并/渲染工具：
- `parse(String text) -> List<String[]>`：按行解析，跳过空行/`#` 注释/非法行（防御性）
- `invalidLine(String text) -> String`：返回第一条非法行（编辑器保存前校验用），全合法返回 null
- `format(List<String[]>) -> String`：渲染回 `KEY=VALUE` 多行文本（编辑器/存储格式）
- `merge(defaults, custom) -> List<String[]>`：LinkedHashMap 叠加，同名覆盖、空值移除
- `envPrefix(pairs) -> String`：`` env 'K=V' 'K=V' … ``（每个 `K=V` 经 `ShellUtils.shQuote`，值含空格也安全；整条命令走 base64 包装，引号可完整穿透）
- `exportLine(pairs) -> String`：`export 'K=V' …`（控制台 preamble 用）
- 变量名校验：`[A-Za-z_][A-Za-z0-9_]*`

### 2. `ds/DsCli.java`
- 删除 `APP_ENV` 常量，新增 `defaultEnvPairs() -> List<String[]>` 返回 6 个内置变量对（含原先行 366 单独追加的 `XDG_SESSION_TYPE`，统一收口）
- `launchApp(name, execArgs, userOverride)` 保留原签名、委托新重载 `launchApp(name, execArgs, userOverride, List<String[]> customEnv)`（customEnv 为 null = 纯默认）；命令拼装改为 `nohup ` + `EnvVars.envPrefix(EnvVars.merge(defaultEnvPairs(), customEnv))`，后续 DBUS/DISPLAY/XAUTHORITY 与 exec 参数拼装不变
- `consolePreamble()` 改为 `consolePreamble(List<String[]> customEnv)`：`cd ~; ` + `EnvVars.exportLine(merge(...))`

### 3. `Prefs.java`
仿照 `launchUser`/`setLaunchUser`（33-40 行）新增：
- `launchEnv(ctx, container) -> String`：键 `launch_env.<container>`，默认 `""`（原文存储多行 KEY=VALUE 文本，与编辑器格式一致，无需 JSON）
- `setLaunchEnv(ctx, container, env)`

### 4. `AppLaunchActivity.java`
`launch()` 中（83 行前）读取 `Prefs.launchEnv(this, container)` 并 `EnvVars.parse`，调用新的 4 参 `DsCli.launchApp`。

### 5. `ShellActivity.java`
- 顶栏 header（82-88 行）在 `userBtn` 与 `refresh` 之间加 `envBtn`（标签「环境」）
- `showEnvEditor()`：AlertDialog，内容为——
  - 小字 TextView 列出内置默认值（`DsCli.defaultEnvPairs()` 格式化），让用户知道基底是什么
  - 等宽多行 EditText，预填当前自定义内容（只存增量，不含内置值）
  - 保存时先 `EnvVars.invalidLine` 校验，非法则 Toast 报错行号内容并阻止保存；合法则 `Prefs.setLaunchEnv` 并提示已保存
  - 按钮：清空（neutral，清空输入框）/ 取消 / 保存
- `updateUserButton()` 附近同步维护 envBtn（标签静态即可）

### 6. `ConsoleActivity.java`
`startSession()`（171-175 行）：读取并 parse 该容器的自定义 env，传给 `consolePreamble(customEnv)`；本地回显的 env 说明改为基于合并后变量对生成（传入拼接的 `K=V` 串）。

### 7. 字符串资源（`values/strings.xml` + `values-zh/strings.xml` 双语）
新增：`env_btn`（环境/ENV）、`env_editor_title_fmt`（启动环境变量 — %1$s）、`env_defaults_fmt`（内置：%1$s）、`env_editor_hint`（每行一个 KEY=VALUE，覆盖内置值；值为空表示移除内置变量；# 为注释）、`env_invalid_line_fmt`（无效的行：%1$s）、`env_saved`（已保存）、`env_clear`（清空）；
修改：`console_env_note` 改为单占位符 `[env exported: %1$s]` / `[环境已导出：%1$s]`。

## 验证
1. `make`（内部 `./gradlew assembleRelease`）编译通过，产出 `build/anland-shell.apk`
2. （可选）android-emulator 安装启动，肉眼确认顶栏按钮与环境变量编辑对话框正常渲染（模拟器上无 droidspaces/su，仅验证 UI）