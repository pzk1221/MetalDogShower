# 测试报告

最后更新：2026-08-09。本文严格区分历史实机事实、v0.2 本地自动测试和 v0.2 生产回归；代码存在、Fake/Mock 通过或上一版使用成功都不能替代 v0.2 真机验证。

## v0.2 本轮范围

按用户要求，本轮先制作与编译验证，不安装到手机或手表，也不进行真实服务或物理浴室测试：

| 项目 | 本轮结果 |
|---|---:|
| v0.2 手机安装 | **0 次 / 未执行** |
| v0.2 手表安装 | **0 次 / 未执行** |
| 真实网络请求 | **0 次** |
| OTP 请求或提交 | **0 次** |
| 真实状态 GET | **0 次** |
| 真实开启/关闭 POST | **0 次** |

用户曾反馈上一版在现场成功完成开启和关闭；它是历史使用反馈，没有计入 v0.2 结果。

## 自动测试

| 套件 | 结果 | 主要覆盖 |
|---|---:|---|
| `core` JVM | **17 tests / 0 failure** | Fake-first、双路由状态隔离、OTP XOR+MD5、精确路径/查询/头/body、401 无 refresh、RequestGate 单飞/去抖/冷却、畸形状态拒绝、已知状态不重复 POST、POST 超时/断连/取消/确认失败不重放、401 确认失败保持不明确 |
| `mobile` JVM | **9 tests / 0 failure** | QR 只本地解析与 host/输入约束、手机会话 codec 双浴室/credentialId 往返、损坏/尾随数据拒绝、跨槽位重复 deviceId 拒绝、provisioning 内层 v2 双浴室 payload |
| `wear` Fake JVM | **9 tests / 0 failure** | 启动不隐式选房、单设备时浴室2未绑定、内层 v1→槽位1兼容、v2 一/双槽位解析、排序、重复 slot/deviceId 与非 canonical credentialId 拒绝、配置替换通知总线 |
| `wear` Real JVM | **9 tests / 0 failure** | 与 Fake 变体运行同一组 flavor-independent 测试逻辑，确认 Real 变体也编译并通过这些规则 |

若按唯一测试逻辑计数，Wear 是 9 个测试；构建矩阵在 Fake 与 Real 两个变体各执行一次，所以报告为“各 9 / 0 failure”，不能误写成 18 个不同测试设计。

## 构建与 lint

| 检查项 | 当前记录 |
|---|---|
| `:core:test` | 通过；17 / 0 |
| `:mobile:testDebugUnitTest` | 通过；9 / 0 |
| Wear Fake 单元测试 | 通过；9 / 0 |
| Wear Real 单元测试 | 通过；9 / 0 |
| mobile compile / assemble | **通过**；Debug APK 与 androidTest APK 均编译成功 |
| wear Fake / Real compile / assemble | **通过**；Fake/Real Debug APK 与 Real androidTest APK 均编译成功 |
| mobile lint | **0 error / 9 warnings**（修复 AppLink/EmptySuper 后最终重跑） |
| wear Real lint | **0 error / 22 warnings** |
| `:mobile:assembleDebugAndroidTest` | 编译成功；androidTest APK **未安装、未运行** |

lint warning 不是“通过测试数”。最终交付若获得更新后的重跑数字，应只替换本表对应数字，不回填猜测。

## 覆盖到的 v0.2 行为

### 双浴室

- 手机/手表模型只接受槽位 1、2 和总计 1–2 间；
- 两个槽位拒绝相同 canonical deviceId，即使 stadiumId 不同；
- Fake repository 的两条路由状态互不串用；
- Wear 启动不自动选择浴室，单设备配置不会伪装成浴室2已绑定。

### 手机持久会话与代际

- phone session codec 往返保存 credentialId、同一 token、两条路由和 watchBound；
- 破损、尾随或重复路由数据被拒绝；
- 生产代码使用 AndroidKeyStore AES-256/GCM 持久化，但 v0.2 本轮没有新增手机仪器测试来证明具体目标手机上的 Keystore 往返。

### Provisioning

- 手机 payload 测试确认加密明文为内层 v2，携带 credentialId 与两条不同槽位路由；
- Watch parser 测试确认内层 v1 单设备兼容，以及 v2 一/双槽位输入约束；
- 外层路径、`version=1` 与 AAD v1 不因内层 schema 升级而改变。

### 控制与不明确结果

- core 测试确认控制 POST 不重放；
- 超时、断连、取消和成功后确认失败都保持安全的不明确语义；
- 路由 A 的未知锁不会污染路由 B；
- 成功状态 GET 才能清除对应路由的未知锁；
- 手机主界面/小组件的“控制前新鲜 GET”属于上层集成逻辑，目前通过代码审查与 core 协议测试间接覆盖，仍需补充专门的 ViewModel/controller 测试或真机回归。

### 小组件

- 当前实现包含 RemoteViews 双浴室选择、最近状态/时间、日夜资源、不同不可变 PendingIntent、`exported=false` Provider/确认 Activity、显式确认和缺少登录/配置时打开主应用；
- 小组件本地显示状态必须已知且不超过 2 分钟，确认后仍做一次新鲜 GET；
- 本轮没有把 v0.2 安装到目标手机 Launcher，因此尺寸、不同厂商桌面、主题切换和点击流程仍是未验证项。

### 双向会话失效

- 生产代码实现 credentialId 精确匹配、手机 `/session/clear`、手表 `/session/invalid`，发送到当时已连接节点且每节点最多一次；
- 本轮没有在线/离线/迟到消息的端到端仪器测试；
- 该机制无 ACK/重试/队列，不得标记为跨设备原子注销。

## 历史实机基线（非 v0.2 回归）

此前读取过的设备：

| 项目 | 历史事实 |
|---|---|
| Watch | Samsung SM-R865U / Galaxy Watch4 |
| Android / SDK | Android 16 / API 36 |
| ABI | armeabi-v7a |
| 屏幕 | 396 × 396，320 dpi |
| 内存档位 | low_ram=true |
| 安全补丁 | 2026-05-05 |
| 调试连接 | ADB TLS 无线配对曾成功 |

上一轮旧实现还曾在该表用随机 Fake 数据完成 AndroidKeyStore/provisioning 仪器测试，并发现 Samsung randomized-encryption key 拒绝调用方提供 GCM IV；修正为由 Cipher 生成 IV 后通过。当时的数字不能直接算作 v0.2 内层 v2、双浴室和双向失效的回归结果。

## 研究阶段网络账本

协议研究阶段固定为 4 次 HTTP：2 次 QR GET、1 次 API 根路径 GET、1 次公开设备映射 GET；当时 0 OTP、0 登录、0 会员、0 状态、0 控制。用户后来正常使用上一版的请求不属于这份研究账本，精确数量未知。

## v0.2 待实机验证

- 手机进程重启后 Keystore 持久登录恢复；
- 两间真实浴室分别解析、选择、刷新，且路由不串用；
- 手机主界面一次开启/确认/关闭/确认；
- 日/夜 Launcher 小组件的布局、状态刷新、确认与未登录引导；
- 内层 provisioning v2 把同一 token 和两条路由同步给目标 Watch；
- 手表双浴室选择和各自状态控制；
- 手机与手表在线时的两向 401/退出通知；
- 离线、迟到旧代际消息与重新同步后的收敛；
- 明确遵守“不得同时从手机和手表控制同一浴室”。

所有真实验证都必须由用户本人明确授权、在浴室旁准备好接水后，以最少动作完成；任何不明确结果都不得重复 POST。
