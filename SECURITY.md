# 安全设计

## 边界

只支持用户本人的正常会员登录和本人浴室设备。项目不包含证书固定绕过、TLS 降级、系统 CA 注入、root/hook、验证码绕过、Cookie/微信数据提取、批量扫描或压力测试能力。

此前对微信进行的域名限定观察在客户端不信任用户 CA/可能存在固定边界处停止；临时 CA、私钥、代理、原始捕获和手机临时文件均已清除。没有取得或保存微信凭据。

## 传输

- API 只允许 `https://api.sbooy.com/`。
- Android `usesCleartextTraffic=false`，Network Security Config 只信任系统 CA。
- 不允许“信任所有证书”、宽松 HostnameVerifier 或 release user-CA 信任。
- 明文二维码 URL 只作为本地短码输入，不携带认证信息访问。
- 禁止自动跨 host 重定向携带 token；HTTP 和 HTTPS 重定向均关闭。
- Release 不启用 HTTP body/header 日志。

## 手机持久凭据

v0.2 不再把手机当作“传完即删 token”的一次性 Setup。手机需要独立控制和驱动桌面小组件，因此会持久保存当前会话：

- token、`credentialId`、1–2 条浴室路由与 `watchBound` 一起编码；
- 使用手机 AndroidKeyStore 不可导出 AES-256/GCM 密钥；
- Cipher 生成随机 12 字节 IV，使用 128 位 GCM tag 与 AAD `MetalDogPhoneSession:v1`；
- SharedPreferences 只保存格式版本、IV 与认证密文；
- 密钥失效、GCM 认证失败、解码越界或重复槽位/设备时 fail closed，删除本地会话并要求重新 OTP；
- 用户退出时删除会话偏好并尝试删除对应 Keystore key；
- 手机 `allowBackup=false`、`fullBackupContent=false`，并配置 data extraction rules 禁止备份和设备迁移。

官方未提供已验证 refresh token，token 的服务端寿命也未知。手机没有 5 分钟硬 TTL；会话保留到用户退出、存储不可恢复或官方受保护接口返回 401。401 不会触发 refresh 或原请求重放。

手机号和 OTP 不写入持久会话。登录成功后清空界面手机号/OTP，失败时清空 OTP。Main Activity 与小组件确认 Activity 均使用 `FLAG_SECURE`，减少系统截图与最近任务缩略图泄漏。

## 手表凭据与共享登录

手机与手表使用同一个官方 token 值，但各自独立加密：

- 手机本地密文由手机 Keystore AES key 保护；
- 手表本地密文由手表自己的 Keystore AES key 保护；
- 两端不共享 Keystore key、IV、偏好文件或本地密文；
- 同步只在受限生命周期内生成 token 字节副本，传输完成或失败后尽量覆盖；
- Watch Real 控制器只在运行期间把解密 token 放入可覆盖字节数组，销毁/失效时覆盖；
- 由于 JSON 与 OkHttp header API 会短暂创建 JVM `String`，无法保证垃圾回收前内存中绝无不可擦除副本。

手表也禁用备份/设备迁移。手表密文使用本机 AndroidKeyStore AES-256/GCM 与 AAD `MetalDogShowerLocalConfig:v1`；目标 Watch4 上必须让 Keystore Cipher 自行生成随机 IV，调用方提供 IV 会被 randomized-encryption key 拒绝。

## 手机到手表的加密同步

1. Watch 在 AndroidKeyStore 生成不可导出的 RSA-2048 私钥。
2. 手机发出随机 `requestId`；Watch 生成 32 字节随机 challenge，并只向对应 source node 返回 X.509 公钥、challenge 和同一 requestId。
3. Watch 的待处理会话绑定 source node、requestId 与 challenge，单次消费；持久化不可逆摘要拒绝进程重启后的重放。
4. 手机每次生成新的临时 AES-256 key 和 12 字节 GCM IV。
5. 动态 AAD 为 `MetalDogShowerProvisioning:v1:<requestId>:<challengeBase64>`。
6. AES key 使用 RSA-OAEP 包裹；OAEP 内容摘要 SHA-256、MGF1 摘要 SHA-1、默认空标签。MGF1-SHA-1 是目标 Watch4 Keystore 的兼容参数，不是把 OAEP 内容摘要降为 SHA-1。
7. Watch 验证版本、来源、长度、单次会话与 GCM tag 后，才把配置用手表本地 Keystore 密钥重新加密。
8. Watch 只有在密文同步写入成功后返回结果；手机总超时 60 秒，超时不自动重发 envelope。60 秒是传输时限，不是 token TTL。

Data Layer 外层路径与 envelope 仍为 v1；加密明文 schema 为 provisioning v2：同一个 `credentialId`、同一个 token，以及 1–2 个带 `slot` 的设备对象。手表兼容旧内层 v1 单设备配置并规范化为槽位1。完整结构见 [AUTH_FLOW.md](AUTH_FLOW.md)。

## credentialId 代际

每次 OTP 登录生成新的 canonical UUID `credentialId`。已登录后改变浴室路由时继续使用同一官方 token，但轮换 credentialId 并将 `watchBound` 置为 false。

所有跨设备清除消息都带 credentialId；接收端只在它与当前持久会话精确匹配时清除。该比较并不能使注销原子化，但可防止迟到的上一代 401/退出通知删除后来重新登录或重新配置的会话。

## 双向失效的真实边界

- 手机退出或收到 401：先清除手机，再向发送当时已连接的 Wear 节点做一轮 `/session/clear` best-effort，每节点最多尝试一次。
- 手表收到 401：先清除手表，再向发送当时已连接的手机节点做一轮 `/session/invalid` best-effort，每节点最多尝试一次。
- payload 只含 `version` 和 `credentialId`，不含 token、手机号或设备路由。
- 没有 ACK、重试、后台队列或服务器 token 撤销端点。
- 节点离线、Play services 超时或进程终止时，另一端可能没有收到消息；跨设备清除是 best-effort，**不是原子事务**。
- 离线端可能暂时保留旧密文；之后只能靠官方 401、自行清除或用户重新同步来收敛。

## 小组件

小组件偏好是刻意分离的非敏感展示状态，只允许保存：

- `isLoggedIn` / `isWatchBound` 显示标记；
- 两个槽位是否已配置；
- `OPEN / CLOSED / UNKNOWN`；
- 最近确认时间；
- 每个 widget 实例当前选中的槽位。

禁止把 token、credentialId、手机号、canonical device id、stadium id 或完整路由加入小组件偏好。`AppWidgetProvider` 不拥有 repository 或凭据，也不进行网络请求；room tab 使用显式不可变 PendingIntent。开启/关闭 PendingIntent 只打开 `exported=false` 的确认 Activity，使用 `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE` 与不同 request code。

小组件控制同时要求：手机会话存在、所选槽位已配置、状态已知、最近本地确认不超过 2 分钟、用户在安全确认页再次确认。随后 controller 仍必须对捕获的 credentialId 和路由执行一次新鲜 GET；GET 失败则不发送 POST。手表是否已同步不是手机控制条件。

控制执行期间 Activity 重建会把结果视为不明确，不恢复成可重试按钮。POST 可能已出站时，任何异常都不能被包装成“安全失败”；小组件改为 UNKNOWN 并禁止重复控制。

## 网络和业务保护

- 手机主界面与小组件共享 Application 级互斥锁；手表有独立互斥锁。
- core RequestGate 单飞、2 秒同类去抖、5 秒总体控制冷却。
- 手机、小组件与手表每次控制前都强制新鲜状态 GET；已是目标状态则不发 POST。
- 控制 POST 不自动重试；模糊结果最多一次状态 GET。
- 401/403/429/业务拒绝不自动重放。
- 未登录、未配置、状态未知或无 INTERNET capability 时禁用/引导控制。
- 设备映射必须核对 `type=shower`；两个槽位禁止复用同一 deviceId。
- 不暴露任意 Base URL、路径或请求头输入，避免变成通用网络工具。

手机和手表的本地锁无法跨设备共享，服务端也没有已验证的幂等键。因此不得同时从手机和手表控制同一间浴室；这是使用规则，不是代码可以完全强制的保证。

## 日志

Release 不记录请求/响应 body，也不记录：

- token / Authorization / Cookie；
- 手机号、验证码；
- openid、unionid、memberId；
- canonical device id、stadium id；
- credentialId、加密 envelope、密钥、IV 或明文配置。

用户界面只显示简短中文错误，不展示 JSON、响应头、堆栈或服务端内部信息。

## 依赖、签名与构建

- Gradle Wrapper 带官方分发 SHA-256。
- Release 开启 R8 与资源压缩。
- 不使用已整体弃用的 `androidx.security:security-crypto`，直接使用平台 AndroidKeyStore。
- 手机与 Real 手表 release APK 必须使用同一 applicationId 和同一签名证书；Fake/Debug 后缀用于隔离测试。
- 发布前必须核对签名、包名、versionCode、debuggable=false、Network Security Config 与备份设置。

## 本轮验证边界

v0.2 本轮没有安装到手机或手表，没有真实网络、OTP、状态或控制请求。当前单元测试为 core 17/0、mobile 9/0、Wear Fake 9/0、Wear Real 9/0；构建与 lint 矩阵见 [TEST_REPORT.md](TEST_REPORT.md)。用户对上一版的现场成功反馈不能替代 v0.2 的安全与回归测试。

如发现凭据进入日志、明文请求、可重复控制、错误路由、跨代际误清除或签名异常，应立即停止真实请求，清除本地凭据，并只在 Fake/Mock 环境复现。
