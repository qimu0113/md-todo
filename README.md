# MD Todo

把 Obsidian 里的 Markdown 待办当成正经待办 App 来用。

这是一个**个人自用**的 Android 应用，从 [Todosian](https://github.com/isotjs/todosian-app) fork 而来（Apache-2.0）。
读取和写入的就是你手机上的 `.md` 文件本身，不导入数据库、不联网、不同步。

> 说明：本项目只在一台设备上验证过（OnePlus Ace 6 Ultimate / Android 16 / ColorOS），
> 其他地方可能有没覆盖到的坑。它是给自己用的，不是面向大众的成品。

---

## 和上游的差异

### 新增

| 功能 | 说明 |
|---|---|
| **容器变换转场** | 点开二级页面时，新页面从被点元素的位置原地展开撑满全屏，返回时缩回同一位置。逆向 ColorOS 便签的转场得出，不是页面平移 |
| **父子文件夹** | 分类按目录层级组织，筛选栏显示扁平路径（`学习` / `学习/高数`），选父级时包含所有子层 |
| **列表 / 宫格双模式** | 移植自 ColorOS 便签，切换时纯交叉淡化 |
| **搜索** | 跨所有 Markdown 文件搜索标签、日期、关键字；全角字符自动折半角 |
| **手动加入今日聚焦** | 「今日聚焦」原本只按到期日筛；现在可以手动 pin 任意任务，pin 优先于到期规则 |
| **中文界面** | 全部 UI 文案 + plurals |
| **Markdown 行内渲染** | `**粗体**` / `*斜体*` / `~~删除~~` / `==高亮==` / `#标签` 在列表里直接渲染 |

### 删除

| 功能 | 原因 |
|---|---|
| 到期提醒通知 | 自用单机不需要后台常驻；顺带砍掉了 `POST_NOTIFICATIONS` 和 `RECEIVE_BOOT_COMPLETED` 权限 |
| 开机自启 | 同上 |
| 更新日志弹窗 | 自己写的，不需要 |
| 每日笔记自动创建 | 当时依赖 WorkManager，与「只处理已有文件」的取向冲突 |
| LeakCanary | 检测到泄漏会抢焦点跳到 Leaks 界面，干扰正常使用 |

### 修正

| 问题 | 处理 |
|---|---|
| 进二级页面返回时 ANR | 根因是凹陷多边形 FAB 形状 + 阴影，Skia 三角剖分死循环。换成圆角矩形 |
| 写回 Markdown 时换行符被改写 | 探测原文件是 CRLF 还是 LF 后按原样写回，否则 Syncthing 会判为双端修改并生成冲突副本 |
| 读取失败即清空文件夹授权 | 改为按权限状态区分提示，不再抹掉用户配置 |
| 子任务勾选不联动父任务 | 补齐双向联动 |

---

## 功能

- 通过 SAF 授权一个文件夹，权限持久化
- 每个 Markdown 文件是一个「分类」，`- [ ]` / `- [x]` 就是待办
- 待办增删改、父子任务、自动勾选联动
- 支持 Obsidian Tasks 插件元数据（`📅` 到期、`⏫` 优先级、`🔁` 重复等）
- 今日聚焦：今天到期 / 已逾期 / 两者都要
- 桌面小组件：待办列表 + 快速添加
- 主题：跟随系统 / 亮 / 暗，Material 3 + 动态取色（Android 12+）
- 排序与分组
- 大文件懒加载

## 支持的 Markdown

```markdown
- [ ] 写作业 📅 2026-10-28 ⏫
- [x] 交报告 ✅ 2026-10-27
  - [ ] 子任务（缩进 2 或 4 空格）
```

## 构建

需要 JDK 21 和 Android SDK（compileSdk 37）。

```bash
# local.properties 里的 sdk.dir 用正斜杠，反斜杠会让 Gradle 解析文件 classpath 时抛 IOException
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`
debug 版包名带 `.debug` 后缀（`com.mdtodo.debug`），可以与正式版共存。

## 数据安全

这几条是硬约束，改代码时别绕过：

- **绝不删除用户的 md 文件**。「0 待办」只在 UI 层过滤，不动文件
- **写回必须保留原换行符**，用 `FileRepository.detectNewline()` 探测后按原样写
- **读取时剥离 BOM**
- 应用没有网络权限，不收集任何数据

## 许可证

Apache License 2.0，见 [LICENSE](LICENSE)。

基于 [Todosian](https://github.com/isotjs/todosian-app)（Copyright 2026 İsmail TİRYAKİ），
原始版权声明保留在 [NOTICE](NOTICE)。本仓库在其基础上做了大量修改，
具体改动见上文「和上游的差异」。

本项目与原作者无关，不代表其立场，也未获得其背书。
