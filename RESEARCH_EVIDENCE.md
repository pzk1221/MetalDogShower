# 研究证据摘要

更新日期：2026-08-09。这里是可随工程交付的脱敏摘要；真实短码、设备 ID、场馆 ID、手机号、credentialId 和 token 均不包含在内。

## 证据等级

- **实测**：从用户提供的文件、设备或一次明确受限请求直接观察。
- **官方 APK 静态验证**：官方 4.1.52 客户端包含该逻辑，但不等于所有生产响应都已实测。
- **用户使用反馈**：用户在正常使用中报告结果，但没有足以重放分析的脱敏网络证据。
- **当前实现**：v0.2 源码中存在并通过相应本地测试的行为，不等于已在生产设备回归。
- **推断 / 未验证**：不能当作已工作。

## 二维码与视频

- 实测：照片与视频帧离线解出同一 `http://26h.fitjs.com/<REDACTED>` 链接。
- 实测：该链接以普通路径和公开 DNS 源站各 GET 一次，均终止于 Aliyun OSS 404 `NoSuchKey`，无 `Location`、无 Cookie、无重定向；不再访问。
- 实测：视频只显示微信扫码后进入“淋浴”页，设备显示“淋浴1”、剩余 20:00、关闭态。视频本身没有点击、状态变化或真实出水证据。
- 实测：扫码时间窗口的微信外部 AppBrand 缓存给出 mini-program appid `wx1fea7145d9193012`；目录没有可读取的小程序包或脚本。
- 推断：微信普通链接二维码到小程序的客户端映射解释了“URL 当前 404 但仍可打开小程序”，而不是 HTTP 重定向。

## 官方 APK

官方来源链：

1. `https://www.jinshugou.com/app.html`
2. `https://bin.sbooy.com/web/tool/26hV3/APP/NFC.html`
3. `https://update-bin.oss-cn-hangzhou.aliyuncs.com/web/tool/26hV3/APP/update/apk-f-t/v4.1.52.apk`

验证结果：

- package `com.jinshugou.app`
- versionName `4.1.52` / versionCode `215`
- minSdk 25 / targetSdk 30 / non-debuggable
- APK SHA-256 `c2328acaba24fd7ad5cac851540d881001972c893b62199ae61f873501a394d9`
- APK Signature Scheme v2、v3 有效；单 signer
- signer certificate SHA-256 `19:69:06:87:32:1D:67:92:A1:71:83:DF:58:FF:BE:92:63:5A:FA:80:39:4E:BC:28:92:C5:60:62:06:D6:3C:5A`

静态代码验证了：官方 HTTPS base URL、短信验证码 key 算法、自定义 `token` 头、无 refresh token/401 回登录、公开设备映射、淋浴状态与 switch 路径、成功后一次状态 GET、本地倒计时而非网络轮询。没有证据支持“token 固定 5 分钟失效”；v0.2 不设置这种 TTL。

## 研究阶段的精确请求账本

协议研究阶段共完成 **4 次** HTTP 请求：

1. 二维码 URL GET。
2. 同一 URL 使用公开 DNS IP 复核的 GET。
3. `https://api.sbooy.com/` 根路径信任检查 GET，body 显示 `1.0.1`。
4. 一次不带认证的公开设备映射 GET，成功得到 shower 类型与“淋浴1”；标识符只保留在本地私有台账。

该研究阶段为 0 次 OTP、0 次登录、0 次会员、0 次淋浴状态、0 次开启、0 次关闭。TLS/SNI-only 连接没有产生 HTTP，不计入 4 次。

这份“4 次”账本只描述早期协议研究，不是项目终生请求总数。用户后来正常使用上一版并反馈成功完成浴室开启和关闭；那是独立的用户使用反馈，精确 OTP/状态/控制次数和脱敏响应没有记录，不能追加到研究账本，也不能用来推断所有 v0.2 行为。

## 既有设备事实

- Samsung Galaxy Watch4 `SM-R865U`
- Android 16 / API 36，安全补丁 2026-05-05
- 396 × 396 / 320 dpi
- armeabi-v7a，`low_ram=true`
- ADB TLS 无线配对曾成功

以上来自此前实机阶段。v0.2 本轮没有重新安装或执行新的手机/手表实机回归。

## v0.2 当前实现

- 手机支持两个固定浴室槽位；解析与持久模型都拒绝重复 slot 和重复 canonical deviceId。
- 手机用 AndroidKeyStore AES-256/GCM 持久保存 token、credentialId、1–2 条路由和 watchBound；没有本地 5 分钟 token TTL。
- 手机主界面可以选择/刷新/控制浴室；手机控制不以 `watchBound` 为前提。
- 手机桌面 RemoteViews 小组件自动适配日夜资源，分别选择浴室1/2，显示最近确认状态与时间；控制必须进入显式确认 Activity，并在 POST 前执行新鲜 GET。
- 手机与手表共享同一个官方 token 值，但分别用各自 Keystore key 加密。
- Data Layer 外层协议仍为 v1；加密明文 schema 为 provisioning v2：`credentialId + token + devices[1..2]`。Watch 兼容旧内层 v1 单设备配置并规范化为槽位1。
- 每次 OTP 登录创建新的 credentialId；已登录后路由改变会轮换 credentialId、保留 token 并使旧手表同步代际失效。
- 手机与手表在 401 时各自先清除本端，再只向当时已连接节点做一轮 secret-free best-effort 失效通知，每节点最多一次；无 ACK/重试/离线队列，不宣称跨设备原子注销。
- 手机/小组件与手表只有各自进程内单飞，无法跨设备互斥；不得同时控制同一浴室。

## v0.2 本地测试摘要

当前收到的最终单元测试计数：

- core JVM：17 tests，0 failure；
- mobile JVM：9 tests，0 failure；
- wear Fake JVM：9 tests，0 failure；
- wear Real JVM：9 tests，0 failure。

Wear Fake/Real 的 8 项来自同一组 flavor-independent 测试逻辑在两个变体各运行一次。覆盖范围和构建/lint 状态见 [TEST_REPORT.md](TEST_REPORT.md)。

这些结果验证本地协议与状态机，不验证生产授权或物理设备效果。

## v0.2 本轮明确未执行

- 未把 v0.2 安装到手机或手表；
- 未发真实网络请求；
- 未请求或提交 OTP；
- 未调用真实状态接口；
- 未从 v0.2 发出开启或关闭；
- 未用 v0.2 验证在线/离线双向失效。

因此，上一版的用户成功反馈可以作为历史可用性线索，但不得改写为“v0.2 已实机通过”。
